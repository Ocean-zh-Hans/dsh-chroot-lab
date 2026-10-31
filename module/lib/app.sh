# shellcheck shell=sh disable=SC3043
# 软件层（DSH 本体）：只读探针 + 版本切换 + 一次性命令。
#
# 边界：node/pnpm/dsh 都在 rootfs 里，所以"取版本"必须进 guest 执行；
# 但 `/opt/dsh` 是宿主目录的只读 bind，所以"换版本"只能改宿主侧的
# `$DSH_RUNTIME_DIR/{current,previous}` 符号链接，且必须先停 chroot。

# guest 侧探针：只读，绝不写任何东西。
DSH_APP_PROBE='
d=/opt/dsh/current/bin/dsh
if [ -x "$d" ]; then
  v=$("$d" --version 2>/dev/null | tail -n 1)
else
  v=
fi
printf "dsh_version=%s\n" "$v"
printf "dsh_bin=%s\n" "$(readlink -f /opt/dsh/current/bin/dsh 2>/dev/null)"
printf "dsh_current=%s\n" "$(readlink -f /opt/dsh/current 2>/dev/null)"
printf "dsh_previous=%s\n" "$(readlink -f /opt/dsh/previous 2>/dev/null)"
printf "node_version=%s\n" "$(node -v 2>/dev/null)"
printf "pnpm_version=%s\n" "$(pnpm -v 2>/dev/null)"
printf "hooks_status=%s\n" "$(head -n 1 /run/dsh/hooks.status 2>/dev/null)"
'

dsh_app_usage() {
  cat <<'EOF'
Usage: dsh-ctl app <subcommand>

Read-only:
  version [--json]        DSH/node/pnpm/hooks 版本与安装来源
  deps [--json]           node/pnpm/dsh 与 hooks 预设、guard 状态
  plugins [--json]        控制面插件（hooks 预设、phone-mcp）与启用状态
  list [--json]           宿主侧已安装的 DSH 版本（versions/）

Write (require --apply, chroot 必须已停止):
  switch <version> --apply [--no-previous]   切换 current 到 versions/<version>
  rollback --apply                           交换 current 与 previous

Guest command (require running):
  exec [--timeout N] -- <command>            在 guest 里跑一条命令并原样返回输出
EOF
}

# 只读：确认 chroot 正在运行并回显 holder pid。
dsh_app_require_running() {
  _app_state=$(dsh_state_get state 2>/dev/null || true)
  _app_pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if [ "$_app_state" != running ] || ! dsh_is_uint "$_app_pid" || ! dsh_proc_alive "$_app_pid"; then
    dsh_err "软件层需要 chroot 正在运行（当前 state=${_app_state:-unknown}）；先 dsh-ctl start --apply"
    return 4
  fi
  printf '%s\n' "$_app_pid"
}

dsh_app_probe() {
  _app_pid=$(dsh_app_require_running) || return 4
  if [ -n "${DSH_APP_PROBE_FILE:-}" ]; then
    # 仅测试用：用一个静态的 key=value 文件代替进 guest。
    cat "$DSH_APP_PROBE_FILE" 2>/dev/null || return 4
    return 0
  fi
  dsh_guest_run "$_app_pid" /bin/sh -c "$DSH_APP_PROBE"
}

dsh_app_value() {
  printf '%s\n' "$1" | sed -n "s/^$2=//p" | head -n 1
}

dsh_app_json_str() {
  _app_s=$(printf '%s' "${1:-}" | tr -d '\000-\037')
  printf '"%s"' "$(printf '%s' "$_app_s" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
}

dsh_app_source() {
  if [ -L "$DSH_RUNTIME_DIR/current" ] || [ -d "$DSH_RUNTIME_DIR/versions" ]; then
    printf 'host\n'
  else
    printf 'baked\n'
  fi
}

dsh_app_versions_list() {
  ls -1 "$DSH_RUNTIME_DIR/versions" 2>/dev/null | while IFS= read -r _v; do
    [ -n "$_v" ] && printf '%s\n' "$_v"
  done
}

dsh_app_hooks_mode() {
  _app_hooks=$1
  case $_app_hooks in
  *'"mode":"full"'*) printf 'full\n' ;;
  *'"mode":"audit-only"'*) printf 'audit-only\n' ;;
  *'"mode":"disabled"'*) printf 'disabled\n' ;;
  *) printf 'unknown\n' ;;
  esac
}

dsh_app_version() {
  _json=0
  case ${1:-} in
  --json) _json=1 ;;
  '') ;;
  *)
    dsh_err "app version: unexpected argument: $1"
    return 2
    ;;
  esac
  _out=$(dsh_app_probe) || return $?
  _ver=$(dsh_app_value "$_out" dsh_version)
  _cur=$(dsh_app_value "$_out" dsh_current)
  _prev=$(dsh_app_value "$_out" dsh_previous)
  _node=$(dsh_app_value "$_out" node_version)
  _pnpm=$(dsh_app_value "$_out" pnpm_version)
  _hooks=$(dsh_app_hooks_mode "$(dsh_app_value "$_out" hooks_status)")
  if [ "$_json" = 1 ]; then
    printf '{"dsh":%s,"current":%s,"previous":%s,"node":%s,"pnpm":%s,"hooks":%s,"source":%s,"versions":[' \
      "$(dsh_app_json_str "$_ver")" "$(dsh_app_json_str "$_cur")" "$(dsh_app_json_str "$_prev")" \
      "$(dsh_app_json_str "$_node")" "$(dsh_app_json_str "$_pnpm")" "$(dsh_app_json_str "$_hooks")" \
      "$(dsh_app_json_str "$(dsh_app_source)")"
    _sep=
    dsh_app_versions_list | while IFS= read -r _v; do
      printf '%s%s' "$_sep" "$(dsh_app_json_str "$_v")"
      _sep=,
    done
    printf ']}\n'
    return 0
  fi
  printf 'dsh       : %s\n' "${_ver:-unknown}"
  printf 'current   : %s\n' "${_cur:-unknown}"
  printf 'previous  : %s\n' "${_prev:-none}"
  printf 'node      : %s\n' "${_node:-unknown}"
  printf 'pnpm      : %s\n' "${_pnpm:-not installed}"
  printf 'hooks     : %s\n' "$_hooks"
  printf 'source    : %s\n' "$(dsh_app_source)"
}

dsh_app_deps() {
  _json=0
  case ${1:-} in
  --json) _json=1 ;;
  '') ;;
  *)
    dsh_err "app deps: unexpected argument: $1"
    return 2
    ;;
  esac
  _out=$(dsh_app_probe) || return $?
  _ver=$(dsh_app_value "$_out" dsh_version)
  _node=$(dsh_app_value "$_out" node_version)
  _pnpm=$(dsh_app_value "$_out" pnpm_version)
  _hooks=$(dsh_app_hooks_mode "$(dsh_app_value "$_out" hooks_status)")
  _guard=$(dsh_read_line "$DSH_GUARD_FILE" 2>/dev/null || true)
  [ -n "$_guard" ] || _guard=off
  if [ "$_json" = 1 ]; then
    printf '{"dsh":%s,"node":%s,"pnpm":%s,"hooks":%s,"guard":%s}\n' \
      "$(dsh_app_json_str "$_ver")" "$(dsh_app_json_str "$_node")" "$(dsh_app_json_str "$_pnpm")" \
      "$(dsh_app_json_str "$_hooks")" "$(dsh_app_json_str "$_guard")"
    return 0
  fi
  printf 'dsh       : %s\n' "${_ver:-unknown}"
  printf 'node      : %s\n' "${_node:-unknown}"
  printf 'pnpm      : %s\n' "${_pnpm:-not installed}"
  printf 'hooks     : %s\n' "$_hooks"
  printf 'guard     : %s\n' "$_guard"
}

# 控制面插件 = 宿主侧 $DSH_CONTROL_DIR 里实际存在的东西；启用状态只报事实，
# 不猜"用户想不想开"。hooks 预设的生效情况以 /run/dsh/hooks.status 为准。
dsh_app_plugins() {
  _json=0
  case ${1:-} in
  --json) _json=1 ;;
  '') ;;
  *)
    dsh_err "app plugins: unexpected argument: $1"
    return 2
    ;;
  esac
  _hooks=$(dsh_read_line "$DSH_GUEST_RUNTIME_DIR/hooks.status" 2>/dev/null || true)
  _mode=$(dsh_app_hooks_mode "$_hooks")
  if [ "$_json" = 1 ]; then
    printf '{"hooks_mode":%s,"items":[' "$(dsh_app_json_str "$_mode")"
    _first=1
    for _preset in hooks.patch.yml hooks-audit-only.patch.yml runtime.patch.yml; do
      [ -f "$DSH_CONTROL_DIR/dsh/$_preset" ] || continue
      _active=false
      case $_mode:$_preset in
      full:hooks.patch.yml) _active=true ;;
      audit-only:hooks-audit-only.patch.yml) _active=true ;;
      esac
      [ "$_first" = 1 ] || printf ','
      _first=0
      printf '{"name":%s,"kind":"preset","active":%s}' "$(dsh_app_json_str "$_preset")" "$_active"
    done
    if [ -d "$DSH_CONTROL_DIR/dsh/phone-mcp" ]; then
      [ "$_first" = 1 ] || printf ','
      _first=0
      printf '{"name":"phone-mcp","kind":"tool","active":true}'
    fi
    printf ']}\n'
    return 0
  fi
  printf 'hooks     : %s\n' "$_mode"
  for _preset in hooks.patch.yml hooks-audit-only.patch.yml runtime.patch.yml; do
    [ -f "$DSH_CONTROL_DIR/dsh/$_preset" ] || continue
    _active=inactive
    case $_mode:$_preset in
    full:hooks.patch.yml | audit-only:hooks-audit-only.patch.yml) _active=active ;;
    esac
    printf 'preset    : %-28s %s\n' "$_preset" "$_active"
  done
  if [ -d "$DSH_CONTROL_DIR/dsh/phone-mcp" ]; then
    printf 'tool      : %-28s %s\n' phone-mcp installed
  fi
}

dsh_app_list() {
  _json=0
  case ${1:-} in
  --json) _json=1 ;;
  '') ;;
  *)
    dsh_err "app list: unexpected argument: $1"
    return 2
    ;;
  esac
  _cur=$(readlink "$DSH_RUNTIME_DIR/current" 2>/dev/null || true)
  _prev=$(readlink "$DSH_RUNTIME_DIR/previous" 2>/dev/null || true)
  if [ "$_json" = 1 ]; then
    printf '{"current":%s,"previous":%s,"versions":[' \
      "$(dsh_app_json_str "$_cur")" "$(dsh_app_json_str "$_prev")"
    _sep=
    dsh_app_versions_list | while IFS= read -r _v; do
      printf '%s%s' "$_sep" "$(dsh_app_json_str "$_v")"
      _sep=,
    done
    printf ']}\n'
    return 0
  fi
  printf 'current   : %s\n' "${_cur:-none}"
  printf 'previous  : %s\n' "${_prev:-none}"
  printf 'versions  :\n'
  dsh_app_versions_list | while IFS= read -r _v; do printf '  %s\n' "$_v"; done
}

# 换版本只动符号链接，且要求 chroot 已停止：运行中的 DSH 用的是旧 inode，
# 换了链接也不会迁移，反而让 status 与 guest 内实际版本不一致。
dsh_app_assert_stopped() {
  _app_state=$(dsh_state_get state 2>/dev/null || true)
  _app_pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if [ "$_app_state" = running ] || { dsh_is_uint "$_app_pid" && dsh_proc_alive "$_app_pid"; }; then
    dsh_err "切换版本前必须先停止 chroot（当前 state=${_app_state:-unknown}）"
    return 4
  fi
  return 0
}

# 原子换链：目标可能是目录，直接 mv 到"指向目录的符号链接"会被解引用、
# 变成把临时链接塞进目录里；所以先删旧链再 rename。
dsh_app_set_link() {
  _link_name=$1
  _link_target=$2
  ln -s "$_link_target" "$DSH_RUNTIME_DIR/$_link_name.tmp.$$" || return 1
  rm -f "$DSH_RUNTIME_DIR/$_link_name" || return 1
  mv -f "$DSH_RUNTIME_DIR/$_link_name.tmp.$$" "$DSH_RUNTIME_DIR/$_link_name" || return 1
}

dsh_app_link_version() {
  _ver=$1
  _target=versions/$_ver
  case $_ver in
  '' | *[!A-Za-z0-9._-]* | .*)
    dsh_err "invalid version name: $_ver"
    return 2
    ;;
  esac
  [ -d "$DSH_RUNTIME_DIR/versions/$_ver" ] || {
    dsh_err "version not installed: $DSH_RUNTIME_DIR/versions/$_ver"
    return 2
  }
  [ -f "$DSH_RUNTIME_DIR/versions/$_ver/bin/dsh" ] && [ -x "$DSH_RUNTIME_DIR/versions/$_ver/bin/dsh" ] || {
    dsh_err "version has no executable bin/dsh: $_ver"
    return 2
  }
  _old=$(readlink "$DSH_RUNTIME_DIR/current" 2>/dev/null || true)
  dsh_step "切换 current → $_target"
  dsh_app_set_link current "$_target" || return 1
  if [ -n "$_old" ] && [ "$_old" != "$_target" ]; then
    dsh_app_set_link previous "$_old" || return 1
  fi
  dsh_log "app: switched current to $_target (previous ${_old:-none})"
  printf 'current   : %s\n' "$_target"
  printf 'previous  : %s\n' "${_old:-none}"
}

dsh_app_switch() {
  _ver=
  _apply=0
  _keep_previous=1
  while [ $# -gt 0 ]; do
    case $1 in
    --apply) _apply=1 ;;
    --no-previous) _keep_previous=0 ;;
    -*)
      dsh_err "app switch: unknown option: $1"
      return 2
      ;;
    *)
      [ -z "$_ver" ] || {
        dsh_err "app switch: only one version allowed"
        return 2
      }
      _ver=$1
      ;;
    esac
    shift
  done
  [ -n "$_ver" ] || {
    dsh_err "app switch: version required"
    return 2
  }
  [ "$_apply" = 1 ] || {
    dsh_err "app switch: --apply required"
    return 3
  }
  dsh_app_assert_stopped || return 4
  if [ "$_keep_previous" = 0 ]; then
    rm -f "$DSH_RUNTIME_DIR/previous"
  fi
  dsh_app_link_version "$_ver"
}

dsh_app_rollback() {
  _apply=0
  while [ $# -gt 0 ]; do
    case $1 in
    --apply) _apply=1 ;;
    *)
      dsh_err "app rollback: unexpected argument: $1"
      return 2
      ;;
    esac
    shift
  done
  [ "$_apply" = 1 ] || {
    dsh_err "app rollback: --apply required"
    return 3
  }
  dsh_app_assert_stopped || return 4
  _cur=$(readlink "$DSH_RUNTIME_DIR/current" 2>/dev/null || true)
  _prev=$(readlink "$DSH_RUNTIME_DIR/previous" 2>/dev/null || true)
  [ -n "$_prev" ] || {
    dsh_err "app rollback: no previous version recorded"
    return 4
  }
  _prev_name=${_prev#versions/}
  dsh_step "回滚 current → $_prev"
  _old=$_cur
  dsh_app_set_link current "$_prev" || return 1
  if [ -n "$_old" ]; then
    dsh_app_set_link previous "$_old" || return 1
  fi
  dsh_log "app: rolled back current to $_prev_name (previous ${_old:-none})"
  printf 'current   : %s\n' "$_prev"
  printf 'previous  : %s\n' "${_old:-none}"
}

# 一次性命令：超时、输出原样返回；退出码就是命令的退出码。
dsh_app_exec() {
  _timeout=${DSH_GUEST_COMMAND_TIMEOUT:-15}
  _cmd=
  while [ $# -gt 0 ]; do
    case $1 in
    --timeout)
      shift
      dsh_is_uint "${1:-}" || {
        dsh_err "app exec: --timeout needs a number"
        return 2
      }
      _timeout=$1
      ;;
    --)
      shift
      _cmd=$*
      break
      ;;
    *)
      dsh_err "app exec: command must follow --"
      return 2
      ;;
    esac
    shift
  done
  [ -n "$_cmd" ] || {
    dsh_err "app exec: empty command"
    return 2
  }
  _app_pid=$(dsh_app_require_running) || return 4
  DSH_GUEST_COMMAND_TIMEOUT=$_timeout dsh_guest_run "$_app_pid" /bin/sh -c "$_cmd"
}

dsh_app_run() {
  _sub=${1:-}
  [ $# -gt 0 ] && shift
  case $_sub in
  version) dsh_app_version "$@" ;;
  deps) dsh_app_deps "$@" ;;
  plugins) dsh_app_plugins "$@" ;;
  list) dsh_app_list "$@" ;;
  switch) dsh_app_switch "$@" ;;
  rollback) dsh_app_rollback "$@" ;;
  exec) dsh_app_exec "$@" ;;
  help | -h | --help | '') dsh_app_usage ;;
  *)
    dsh_err "app: unknown subcommand: $_sub"
    dsh_app_usage >&2
    return 2
    ;;
  esac
}
