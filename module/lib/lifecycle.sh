# shellcheck shell=sh disable=SC3043
# Lifecycle: start/stop/restart/boot/diag/log/fixperm.
# All commands are gated: start only runs when the caller passed --apply.
# doctor/plan/status never load or call these functions.

dsh_selinux_read() {
  _v=$(dsh_read_line "$DSH_SYS/fs/selinux/enforce" 2>/dev/null || true)
  dsh_is_uint "$_v" && printf '%s\n' "$_v"
}

dsh_selinux_set() {
  _target=$1
  if command -v setenforce >/dev/null 2>&1; then
    setenforce "$_target"
  else
    /system/bin/setenforce "$_target"
  fi
}

dsh_selinux_save_and_disable() {
  _orig=$(dsh_selinux_read || true)
  if [ -z "$_orig" ]; then
    dsh_err "cannot read SELinux enforce state; refusing --permissive"
    return 1
  fi
  printf '%s\n' "$_orig" >"$DSH_CTL_DIR/selinux.orig" || return 1
  dsh_warn "break-glass: setenforce 0; stop will restore enforce=$_orig"
  dsh_selinux_set 0 || {
    rm -f "$DSH_CTL_DIR/selinux.orig"
    dsh_err "setenforce 0 failed"
    return 1
  }
}

dsh_selinux_restore() {
  [ -f "$DSH_CTL_DIR/selinux.orig" ] || return 0
  _orig=$(dsh_read_line "$DSH_CTL_DIR/selinux.orig" 2>/dev/null || true)
  case $_orig in
  0 | 1) ;;
  *)
    dsh_warn "invalid saved SELinux state: ${_orig:-empty}"
    return 1
    ;;
  esac
  dsh_selinux_set "$_orig" || {
    dsh_err "failed to restore SELinux enforce=$_orig"
    return 1
  }
  rm -f "$DSH_CTL_DIR/selinux.orig"
  dsh_log "SELinux enforce restored to $_orig"
}

dsh_nsenter_supports_fd() {
  _nsenter=$(dsh_tool_path nsenter || true)
  [ -n "$_nsenter" ] || return 1
  "$_nsenter" --help 2>&1 | grep -q -- '--mount'
}

dsh_guest_run() {
  # Runs inside the holder's mount namespace, chrooted to the rootfs.
  # Caller must hold fd 9 open on /proc/<holder>/ns/mnt.
  _pid=$1
  shift
  # Re-check birth identity immediately before entering; nsenter opens the
  # namespace path itself. Avoid shell `exec 9<...`: a denied special-builtin
  # redirection can terminate a non-interactive mksh.
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  dsh_proc_validate_identity "$_pid" "$_st" "$_ns" || {
    dsh_err "holder identity changed before nsenter: $_pid"
    return 1
  }
  _nsenter=$(dsh_tool_path nsenter || true)
  if [ -z "$_nsenter" ]; then
    dsh_err "nsenter not found"
    return 1
  fi
  if dsh_nsenter_supports_fd; then
    "$_nsenter" --mount="/proc/$_pid/ns/mnt" chroot "$DSH_ROOTFS_DIR" "$@"
  else
    "$_nsenter" -t "$_pid" -m chroot "$DSH_ROOTFS_DIR" "$@"
  fi
}

dsh_guest_shell() {
  _pid=$1
  _script=$2
  dsh_guest_run "$_pid" /bin/sh -c "$_script"
}

dsh_start_usage() {
  cat <<'EOF'
Usage: dsh-ctl start [--protect-critical=off] [--permissive] [--apply]

--apply is mandatory in P2; without it the command only prints what would run.
--permissive is an explicit break-glass; stop/watchdog restores the saved state.
EOF
}

dsh_start_locked() {
  _protect=$DSH_PROTECT_CRITICAL
  _permissive=0
  _apply=0
  for _arg in "$@"; do
    case $_arg in
    --protect-critical=off | --protect-critical=0) _protect=0 ;;
    --protect-critical=on | --protect-critical=1) _protect=1 ;;
    --permissive) _permissive=1 ;;
    --apply) _apply=1 ;;
    -h | --help)
      dsh_start_usage
      return 0
      ;;
    *)
      dsh_err "unknown start option: $_arg"
      return 2
      ;;
    esac
  done

  if [ "$_apply" -ne 1 ]; then
    dsh_err "start requires the explicit --apply flag"
    dsh_plan_run | sed -n '1,45p'
    return 3
  fi

  if [ "$(dsh_state_get state 2>/dev/null || true)" = running ]; then
    _old_pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
    if dsh_is_uint "$_old_pid" && dsh_proc_alive "$_old_pid"; then
      dsh_err "already running (holder pid $_old_pid)"
      return 1
    fi
  fi

  dsh_doctor_run --for-apply || {
    dsh_state_set state=failed note=doctor-failed
    return 4
  }

  if [ "$_permissive" -eq 1 ]; then
    dsh_selinux_save_and_disable || return 1
  fi

  dsh_state_set state=starting note=
  rm -f "$DSH_PID_FILE" "$DSH_STARTTIME_FILE" "$DSH_NS_FILE" "$DSH_URL_FILE" "$DSH_LOOP_FILE"
  mkdir -p "$DSH_ROOTFS_DIR" || return 1
  chmod 0700 "$DSH_ROOTFS_DIR" 2>/dev/null || true
  if [ -n "$(ls -A "$DSH_ROOTFS_DIR" 2>/dev/null || true)" ]; then
    dsh_err "rootfs mountpoint is not empty: $DSH_ROOTFS_DIR"
    dsh_state_set state=failed note=mountpoint-not-empty
    return 1
  fi

  dsh_e2fsck_prepare "$DSH_IMAGE" || {
    dsh_state_set state=failed note=e2fsck-failed
    return 1
  }
  _loop=$(dsh_loop_attach "$DSH_IMAGE") || {
    dsh_state_set state=failed note=losetup-failed
    return 1
  }
  printf '%s\n' "$_loop" >"$DSH_LOOP_FILE"
  dsh_log "image attached: $_loop"

  _unshare=$(dsh_tool_path unshare || true)
  if [ -z "$_unshare" ]; then
    dsh_err "unshare not found"
    dsh_loop_detach "$_loop" || true
    dsh_state_set state=failed note=unshare-missing
    return 1
  fi

  _log=$DSH_LOG_DIR/nsinit.log
  mkdir -p "$DSH_LOG_DIR" || true
  (
    DSH_APPLY=1 \
      DSH_LOOP="$_loop" \
      DSH_PROTECT_CRITICAL="$_protect" \
      DSH_SELINUXFS_MASK="$DSH_SELINUXFS_MASK" \
      setsid "$_unshare" --mount --propagation private --uts -m "$DSH_NSINIT"
  ) >>"$_log" 2>&1 &
  _launcher=$!
  dsh_log "launcher pid=$_launcher; waiting for holder pid"

  if ! dsh_wait_pid_identity "$DSH_PID_FILE" 20; then
    dsh_err "holder did not publish a valid pid; see $_log"
    dsh_state_set state=failed note=holder-pid-timeout
    _maybe=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
    if ! dsh_is_uint "$_maybe" || ! dsh_proc_alive "$_maybe"; then
      dsh_loop_detach "$_loop" || true
    fi
    return 1
  fi

  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  _host_ns=$(dsh_proc_ns "$$" 2>/dev/null || true)
  if [ -z "$_ns" ] || [ "$_ns" = "$_host_ns" ]; then
    if [ "${DSH_TEST_ALLOW_SAME_NS:-0}" != 1 ]; then
      dsh_err "holder is not in a distinct mount namespace; refusing start"
      kill -TERM "$_pid" 2>/dev/null || true
      _i=0
      while [ "$_i" -lt 10 ] && dsh_proc_alive "$_pid"; do
        sleep 1
        _i=$((_i + 1))
      done
      dsh_loop_detach "$_loop" || true
      dsh_clear_runtime_files
      dsh_state_set state=failed note=namespace-not-private
      return 1
    fi
    dsh_warn "test override: holder shares caller mount namespace"
  fi
  dsh_state_set "state=starting" "pid=$_pid" "starttime=$_st" "ns=$_ns" "loop=$_loop" "image=$DSH_IMAGE" "version=$DSH_VERSION"

  if ! dsh_wait_file "$DSH_URL_FILE" "${DSH_START_TIMEOUT:-90}"; then
    if dsh_proc_alive "$_pid"; then
      dsh_err "DSH URL not ready; holder kept alive for diagnosis"
      dsh_state_set "state=degraded" "note=url-timeout" "pid=$_pid" "starttime=$_st" "ns=$_ns" "loop=$_loop"
    else
      dsh_err "holder exited before URL; detaching loop"
      dsh_loop_detach "$_loop" || true
      dsh_clear_runtime_files
      dsh_state_set state=failed note=holder-exited-before-url
    fi
    return 4
  fi

  _url=$(dsh_read_line "$DSH_URL_FILE" 2>/dev/null || true)
  dsh_state_set "state=running" "pid=$_pid" "starttime=$_st" "ns=$_ns" "loop=$_loop" "image=$DSH_IMAGE" "url=$_url" "started=$(dsh_timestamp)" "version=$DSH_VERSION"
  printf '%s\n' "1" >"$DSH_CTL_DIR/autostart" 2>/dev/null || true
  printf '%s\n' "$DSH_IMAGE" >"$DSH_LAST_GOOD_FILE" 2>/dev/null || true
  printf '0\n' >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
  dsh_log "started: holder=$_pid url=$_url"
  printf 'started: pid=%s\nurl: %s\n' "$_pid" "$_url"
  return 0
}

dsh_start() {
  dsh_lock_acquire || return 1
  dsh_start_locked "$@"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_stop_locked() {
  _state=$(dsh_state_get state 2>/dev/null || true)
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  _loop=$(dsh_read_line "$DSH_LOOP_FILE" 2>/dev/null || true)

  if ! dsh_is_uint "$_pid" || ! dsh_proc_alive "$_pid"; then
    dsh_warn "no live holder (pid ${_pid:-none})"
    if [ -n "$_loop" ]; then dsh_loop_detach "$_loop" || true; fi
    dsh_selinux_restore || true
    dsh_clear_runtime_files
    [ "$_state" = stopped ] || dsh_state_set state=stopped note=no-live-holder
    return 0
  fi
  if ! dsh_proc_validate_identity "$_pid" "$_st" "$_ns"; then
    dsh_err "holder identity mismatch (pid reuse?); refusing to signal pid $_pid"
    dsh_state_set state=stale note=identity-mismatch
    return 4
  fi

  dsh_state_set "state=stopping" "pid=$_pid" "starttime=$_st" "ns=$_ns" "loop=$_loop"

  dsh_guest_shell "$_pid" 'sync; sync' 2>/dev/null || dsh_warn "guest sync failed"
  for _svc in dsh ttyd; do
    dsh_guest_run "$_pid" /usr/bin/s6-svc -d "/run/s6/$_svc" 2>/dev/null || true
  done
  for _svc in dsh ttyd; do
    dsh_guest_run "$_pid" /usr/bin/s6-svwait -d -t 10000 "/run/s6/$_svc" 2>/dev/null || true
  done

  # Signal only the validated holder. The holder forwards TERM to its s6
  # child; killing the whole process group can hit the caller's own group.
  kill -TERM "$_pid" 2>/dev/null || true
  _i=0
  while [ "$_i" -lt 10 ]; do
    dsh_proc_alive "$_pid" || break
    sleep 1
    _i=$((_i + 1))
  done
  if dsh_proc_alive "$_pid"; then
    dsh_warn "holder did not exit; KILL pid $_pid"
    kill -KILL "$_pid" 2>/dev/null || true
  fi

  # Kill any surviving processes in the same mount namespace before detaching
  # the loop. The holder is the only intended owner; this is the zero-residue
  # backstop for a SIGKILL/race. The mock test shares the caller namespace, so
  # it must opt out explicitly.
  _host_ns=$(dsh_proc_ns "$$" 2>/dev/null || true)
  if [ -n "$_ns" ] && [ "$_ns" != "$_host_ns" ] && [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ]; then
    for _p in $(dsh_proc_scan_ns "$_ns" 2>/dev/null || true); do
      [ "$_p" = "$$" ] && continue
      kill -TERM "$_p" 2>/dev/null || true
    done
    sleep 1 2>/dev/null || true
    for _p in $(dsh_proc_scan_ns "$_ns" 2>/dev/null || true); do
      [ "$_p" = "$$" ] && continue
      kill -KILL "$_p" 2>/dev/null || true
    done
  fi

  if [ -n "$_loop" ]; then
    dsh_loop_detach "$_loop" || dsh_warn "loop detach failed: $_loop"
  fi
  dsh_selinux_restore || true
  dsh_clear_runtime_files
  dsh_state_set "state=stopped" "stopped=$(dsh_timestamp)"
  dsh_log "stopped"
  return 0
}

dsh_stop() {
  dsh_lock_acquire || return 1
  dsh_stop_locked
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_restart() {
  dsh_stop || return 1
  dsh_start --apply "$@"
}

dsh_boot() {
  _timeout=${DSH_BOOT_TIMEOUT:-300}
  _i=0
  while [ "$_i" -lt "$_timeout" ]; do
    _boot=$(getprop sys.boot_completed 2>/dev/null || true)
    _ce=$(getprop sys.user.0.ce_available 2>/dev/null || true)
    [ "$_boot" = 1 ] && [ "$_ce" = 1 ] && break
    sleep 2
    _i=$((_i + 2))
  done
  if [ "$_i" -ge "$_timeout" ]; then
    dsh_err "boot wait timed out"
    return 1
  fi
  _auto=$(dsh_read_line "$DSH_CTL_DIR/autostart" 2>/dev/null || true)
  [ "$_auto" = 1 ] || {
    dsh_log "boot: autostart is off"
    return 0
  }
  _state=$(dsh_state_get state 2>/dev/null || true)
  [ "$_state" = rescue-needed ] && {
    dsh_log "boot: rescue-needed is set; autostart suppressed"
    return 0
  }
  if dsh_start --apply; then
    printf '0\n' >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
    return 0
  fi
  _fails=$(dsh_read_line "$DSH_CTL_DIR/boot-fails" 2>/dev/null || true)
  dsh_is_uint "$_fails" || _fails=0
  _fails=$((_fails + 1))
  printf '%s\n' "$_fails" >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
  dsh_err "boot start failed (${_fails}/3)"
  if [ "$_fails" -ge 3 ]; then
    dsh_state_set state=rescue-needed note=boot-failures
    dsh_err "three boot failures; manual rescue required: dsh-ctl rescue --apply"
  fi
  return 1
}

dsh_diag() {
  printf '== status ==\n'
  dsh_status_run
  printf '\n== doctor ==\n'
  dsh_doctor_run || true
  printf '\n== paths ==\n'
  dsh_config_dump
  printf '\n== loop ==\n'
  _loop=$(dsh_read_line "$DSH_LOOP_FILE" 2>/dev/null || true)
  printf 'recorded loop: %s\n' "${_loop:-none}"
  printf '\n== recent log ==\n'
  [ -r "$DSH_LOG_DIR/dsh-ctl.log" ] && tail -n 30 "$DSH_LOG_DIR/dsh-ctl.log" || true
}

dsh_logs() {
  _n=${1:-100}
  case $_n in '' | *[!0-9]*) _n=100 ;; esac
  for _f in "$DSH_LOG_DIR/dsh-ctl.log" "$DSH_LOG_DIR/nsinit.log" "$DSH_LOG_DIR/boot.log"; do
    [ -r "$_f" ] || continue
    printf '== %s ==\n' "$_f"
    tail -n "$_n" "$_f"
  done
}

dsh_fixperm() {
  _ws=$DSH_MEDIA/DSH/workspace
  mkdir -p "$_ws" || return 1
  chmod 2777 "$_ws" || return 1
  find "$_ws" -type d -exec chmod 2777 {} + 2>/dev/null || true
  find "$_ws" -type f -exec chmod 0666 {} + 2>/dev/null || true
  printf 'workspace permissions fixed: %s\n' "$_ws"
}

dsh_rescue() {
  _apply=0
  for _arg in "$@"; do
    case $_arg in
    --apply) _apply=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl rescue --apply\n'
      return 0
      ;;
    *)
      dsh_err "unknown rescue option: $_arg"
      return 2
      ;;
    esac
  done
  if [ "$_apply" -ne 1 ]; then
    dsh_err "rescue requires the explicit --apply flag"
    printf 'rescue: stop the main holder, mount rootfs ro, then open /bin/bash\n'
    printf 'No /data, /storage, or /sdcard mounts are made in rescue.\n'
    return 3
  fi
  dsh_stop || true
  dsh_lock_acquire || return 1
  dsh_e2fsck_prepare "$DSH_IMAGE" || {
    dsh_lock_release
    return 1
  }
  _loop=$(dsh_loop_attach "$DSH_IMAGE") || {
    dsh_lock_release
    return 1
  }
  _unshare=$(dsh_tool_path unshare || true)
  [ -n "$_unshare" ] || {
    dsh_err "unshare not found"
    dsh_loop_detach "$_loop" || true
    dsh_lock_release
    return 1
  }
  printf 'rescue: rootfs is read-only; no /data/storage; exit bash to clean up\n' >&2
  (
    DSH_RESCUE=1 DSH_APPLY=1 DSH_LOOP="$_loop" \
      "$_unshare" --mount --propagation private --uts -m "$DSH_NSINIT"
  )
  _rc=$?
  dsh_loop_detach "$_loop" || dsh_warn "rescue loop detach failed: $_loop"
  dsh_clear_runtime_files
  dsh_lock_release
  return "$_rc"
}
