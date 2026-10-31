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

Install (require --apply, chroot 必须正在运行):
  install --from registry --version <v> --apply [--activate] [--force]
  install --from tarball  --url <https url> --apply [--version <v>] [--sha256 <hex>] [--activate]
  install --from local    --file <guest path(.tgz|dir)> --apply [--version <v>] [--activate]
                          取到 versions/.staging.* 校验后原子发布为 versions/<v>
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

# ---- 安装 ---------------------------------------------------------------
# 取包必须进 guest（node/npm/curl 都在 rootfs 里），但落点 /opt/dsh 是宿主目录的
# bind，宿主能直接读，所以"下载/解包"在 guest、"校验/发布"在宿主。发布是原子 rename，
# 失败只会留下可清的 .staging.*，不会污染 versions/ 或 current。
dsh_app_default_version() {
  _dv=$(basename "${1%/}")
  case $_dv in
  *.tar.gz) _dv=${_dv%.tar.gz} ;;
  *.tgz) _dv=${_dv%.tgz} ;;
  *.tar) _dv=${_dv%.tar} ;;
  esac
  printf '%s\n' "$_dv"
}

dsh_app_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v busybox >/dev/null 2>&1; then
    busybox sha256sum "$1" | awk '{print $1}'
  else
    return 1
  fi
}

dsh_app_install_fail() {
  _app_fetch_err=$1
  [ -n "${_app_stage:-}" ] && rm -rf "$_app_stage"
  dsh_err "$1"
  return 4
}

# guest 侧取包；失败时把原因写进 _app_fetch_err 并返回非零。
dsh_app_fetch() {
  case $_app_from in
  registry)
    _app_pkg=${DSH_APP_PACKAGE:-@deepseek-ai/dsh}
    DSH_GUEST_COMMAND_TIMEOUT=$_app_tmo dsh_guest_shell "$_app_pid" "set -e
if command -v npm >/dev/null 2>&1; then
  npm install --global --prefix '$_app_gdir' '$_app_pkg@$_app_ver' >/dev/null 2>&1
elif command -v pnpm >/dev/null 2>&1; then
  pnpm add --global --prefix '$_app_gdir' '$_app_pkg@$_app_ver' >/dev/null 2>&1
else
  echo 'guest has neither npm nor pnpm' >&2
  exit 1
fi" || {
      _app_fetch_err="app install: guest 下载 $_app_pkg@$_app_ver 失败（网络或包名？DSH_APP_PACKAGE=$_app_pkg）"
      return 4
    }
    ;;
  tarball)
    DSH_GUEST_COMMAND_TIMEOUT=$_app_tmo dsh_guest_shell "$_app_pid" "set -e
command -v curl >/dev/null 2>&1 || { echo 'guest has no curl' >&2; exit 1; }
curl -fsSL --max-time '$_app_tmo' '$_app_url' -o '$_app_gdir/.install.tgz'" || {
      _app_fetch_err="app install: 下载 $_app_url 失败"
      return 4
    }
    ;;
  local)
    DSH_GUEST_COMMAND_TIMEOUT=$_app_tmo dsh_guest_shell "$_app_pid" "set -e
[ -e '$_app_file' ] || { echo 'not found: $_app_file' >&2; exit 1; }
if [ -d '$_app_file' ]; then
  cp -a '$_app_file/.' '$_app_gdir/'
else
  cp -f '$_app_file' '$_app_gdir/.install.tgz'
fi" || {
      _app_fetch_err="app install: 复制 $_app_file 失败"
      return 4
    }
    ;;
  esac
  return 0
}

dsh_app_install() {
  _app_from=
  _app_ver=
  _app_url=
  _app_file=
  _app_sha=
  _app_force=0
  _app_activate=0
  _app_apply=0
  _app_tmo=${DSH_APP_INSTALL_TIMEOUT:-900}
  _app_stage=
  while [ $# -gt 0 ]; do
    case $1 in
    --from)
      shift
      _app_from=${1:-}
      ;;
    --version)
      shift
      _app_ver=${1:-}
      ;;
    --url)
      shift
      _app_url=${1:-}
      ;;
    --file)
      shift
      _app_file=${1:-}
      ;;
    --sha256)
      shift
      _app_sha=${1:-}
      ;;
    --force) _app_force=1 ;;
    --activate) _app_activate=1 ;;
    --apply) _app_apply=1 ;;
    -*)
      dsh_err "app install: unknown option: $1"
      return 2
      ;;
    *)
      dsh_err "app install: unexpected argument: $1"
      return 2
      ;;
    esac
    shift
  done
  [ "$_app_apply" = 1 ] || {
    dsh_err "app install: --apply required"
    return 3
  }
  case $_app_from in
  registry)
    [ -n "$_app_ver" ] || {
      dsh_err "app install --from registry: --version required"
      return 2
    }
    ;;
  tarball)
    [ -n "$_app_url" ] || {
      dsh_err "app install --from tarball: --url required"
      return 2
    }
    case $_app_url in
    https://* | http://*) ;;
    *)
      dsh_err "app install: --url must be http(s)"
      return 2
      ;;
    esac
    ;;
  local)
    [ -n "$_app_file" ] || {
      dsh_err "app install --from local: --file required"
      return 2
    }
    case $_app_file in
    /*) ;;
    *)
      dsh_err "app install: --file must be an absolute guest path"
      return 2
      ;;
    esac
    case $_app_file in
    *..*)
      dsh_err "app install: --file must not contain .."
      return 2
      ;;
    esac
    ;;
  *)
    dsh_err "app install: --from registry|tarball|local"
    return 2
    ;;
  esac
  [ -n "$_app_ver" ] || _app_ver=$(dsh_app_default_version "${_app_url:-$_app_file}")
  case $_app_ver in
  '' | *[!A-Za-z0-9._-]* | .* | -*)
    dsh_err "app install: invalid version name: $_app_ver"
    return 2
    ;;
  esac
  [ ! -e "$DSH_RUNTIME_DIR/versions/$_app_ver" ] || {
    dsh_err "app install: versions/$_app_ver already exists (remove it first to reinstall)"
    return 4
  }
  _app_pid=$(dsh_app_require_running) || return 4
  [ -d "$DSH_RUNTIME_DIR/versions" ] || mkdir -p "$DSH_RUNTIME_DIR/versions" || return 4
  _app_stage=$DSH_RUNTIME_DIR/versions/.staging.$$
  _app_gdir=/opt/dsh/versions/.staging.$$
  rm -rf "$_app_stage"
  mkdir -p "$_app_stage" && chmod 0755 "$_app_stage" || {
    dsh_err "app install: cannot create $_app_stage"
    return 4
  }
  dsh_step "安装 DSH $_app_ver（$_app_from）"
  if [ -n "${DSH_APP_INSTALL_FIXTURE_DIR:-}" ]; then
    # 仅测试用：把夹具目录当成"已经下载好的包"，完全不进 guest。
    cp -a "$DSH_APP_INSTALL_FIXTURE_DIR/." "$_app_stage/" || dsh_app_install_fail "app install: fixture copy failed" || return 4
  else
    dsh_app_fetch || {
      dsh_app_install_fail "$_app_fetch_err" || return 4
      return 4
    }
  fi
  if [ -f "$_app_stage/.install.tgz" ]; then
    if [ -n "$_app_sha" ]; then
      _got_sha=$(dsh_app_sha256 "$_app_stage/.install.tgz") || dsh_app_install_fail "app install: no sha256sum available to verify --sha256" || return 4
      [ "$_got_sha" = "$_app_sha" ] || dsh_app_install_fail "app install: sha256 mismatch (want $_app_sha, got $_got_sha)" || return 4
      dsh_step "sha256 校验通过"
    fi
    DSH_GUEST_COMMAND_TIMEOUT=$_app_tmo dsh_guest_shell "$_app_pid" "set -e
tar -xzf '$_app_gdir/.install.tgz' -C '$_app_gdir'
rm -f '$_app_gdir/.install.tgz'" || dsh_app_install_fail "app install: 解包失败" || return 4
  fi
  # 有些打包会多一层顶层目录：只有一个子目录、且子目录里有 bin/dsh 时，把它提上来。
  if [ ! -f "$_app_stage/bin/dsh" ]; then
    _entries=$(find "$_app_stage" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')
    _subdir=$(find "$_app_stage" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    if [ "$_entries" = 1 ] && [ -n "$_subdir" ] && [ -f "$_subdir/bin/dsh" ]; then
      (cd "$_subdir" && tar cf - .) | (cd "$_app_stage" && tar xf -) || dsh_app_install_fail "app install: 提升顶层目录失败" || return 4
      rm -rf "$_subdir"
    fi
  fi
  [ -f "$_app_stage/bin/dsh" ] || dsh_app_install_fail "app install: 包内没有 bin/dsh（现有：$(ls "$_app_stage" 2>/dev/null | tr '\n' ' ')）" || return 4
  chmod 0755 "$_app_stage/bin/dsh" 2>/dev/null || true
  [ -x "$_app_stage/bin/dsh" ] || dsh_app_install_fail "app install: bin/dsh 不可执行" || return 4
  if [ "$_app_force" = 0 ] && [ -z "${DSH_APP_INSTALL_FIXTURE_DIR:-}" ]; then
    _want=$(printf '%s' "$_app_ver" | sed 's/^v//')
    _got_ver=$(DSH_GUEST_COMMAND_TIMEOUT=$_app_tmo dsh_guest_run "$_app_pid" "$_app_gdir/bin/dsh" --version 2>/dev/null | tail -n 1 | sed 's/^v//' | tr -d '\r') || _got_ver=
    if [ -n "$_got_ver" ] && [ "$_got_ver" != "$_want" ]; then
      dsh_app_install_fail "app install: 新版本自报 $_got_ver，与请求的 $_want 不一致（确认无误可加 --force）" || return 4
    fi
    [ -n "$_got_ver" ] || dsh_warn "app install: 新版本没能自报版本号，跳过版本比对"
  fi
  {
    printf 'source=%s\n' "$_app_from"
    printf 'version=%s\n' "$_app_ver"
    [ -n "$_app_url" ] && printf 'url=%s\n' "$_app_url"
    [ -n "$_app_file" ] && printf 'file=%s\n' "$_app_file"
    [ -n "$_app_sha" ] && printf 'sha256=%s\n' "$_app_sha"
    printf 'installed_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || true)"
  } >"$_app_stage/.dsh-install" || dsh_app_install_fail "app install: 写安装信息失败" || return 4
  dsh_step "发布 versions/$_app_ver"
  mv "$_app_stage" "$DSH_RUNTIME_DIR/versions/$_app_ver" || dsh_app_install_fail "app install: 发布 versions/$_app_ver 失败" || return 4
  _app_stage=
  dsh_log "app: installed $_app_ver from $_app_from"
  printf 'installed : versions/%s\n' "$_app_ver"
  if [ "$_app_activate" = 1 ]; then
    if dsh_app_assert_stopped 2>/dev/null; then
      dsh_app_link_version "$_app_ver"
    else
      dsh_warn "chroot 正在运行，未激活；停止后执行 dsh-ctl app switch $_app_ver --apply"
    fi
  else
    printf 'next      : dsh-ctl app switch %s --apply（需先停止 chroot）\n' "$_app_ver"
  fi
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
  install) dsh_app_install "$@" ;;
  help | -h | --help | '') dsh_app_usage ;;
  *)
    dsh_err "app: unknown subcommand: $_sub"
    dsh_app_usage >&2
    return 2
    ;;
  esac
}
