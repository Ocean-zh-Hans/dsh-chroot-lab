# shellcheck shell=sh disable=SC3043
# Lifecycle: start/stop/restart/boot/diag/log/fixperm.
# All commands are gated: start only runs when the caller passed --apply.
# doctor/plan/status never attach, mount, unmount, or detach anything.

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
  dsh_bounded 5 "$_nsenter" --mount=/proc/self/ns/mnt -- /system/bin/true >/dev/null 2>&1
}

dsh_guest_run() {
  local _pid="$1" _st _ns _nsenter _guest_timeout
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
  _guest_timeout=${DSH_GUEST_COMMAND_TIMEOUT:-15}
  if dsh_nsenter_supports_fd; then
    dsh_bounded "$_guest_timeout" "$_nsenter" --mount="/proc/$_pid/ns/mnt" -- chroot "$DSH_ROOTFS_DIR" \
      /usr/bin/env -i HOME=/root PATH=/opt/node/bin:/usr/bin:/bin \
      TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color \
      /bin/sh -c '. /opt/dsh-control/bin/dsh-android-env || { echo "Android environment setup failed" >&2; exit 1; }; exec "$@"' sh "$@"
  else
    dsh_bounded "$_guest_timeout" "$_nsenter" -t "$_pid" -m -- chroot "$DSH_ROOTFS_DIR" \
      /usr/bin/env -i HOME=/root PATH=/opt/node/bin:/usr/bin:/bin \
      TMPDIR=/tmp LANG=C.UTF-8 TERM=xterm-256color \
      /bin/sh -c '. /opt/dsh-control/bin/dsh-android-env || { echo "Android environment setup failed" >&2; exit 1; }; exec "$@"' sh "$@"
  fi
}

dsh_guest_shell() {
  _pid=$1
  _script=$2
  dsh_guest_run "$_pid" /bin/sh -c "$_script"
}

dsh_nsenter_any() {
  local _pid="$1" _ns="$2"
  shift 2
  dsh_is_uint "$_pid" && dsh_proc_alive "$_pid" || return 1
  [ "$(dsh_proc_ns "$_pid" 2>/dev/null)" = "$_ns" ] || return 1
  dsh_proc_ns_has_rootfs "$_pid" "$DSH_ROOTFS_DIR" || return 1
  local _nsenter
  _nsenter=$(dsh_tool_path nsenter || true)
  [ -n "$_nsenter" ] || return 1
  if dsh_nsenter_supports_fd; then
    dsh_bounded "${DSH_RECOVERY_COMMAND_TIMEOUT:-120}" "$_nsenter" --mount="/proc/$_pid/ns/mnt" -- "$@"
  else
    dsh_bounded "${DSH_RECOVERY_COMMAND_TIMEOUT:-120}" "$_nsenter" -t "$_pid" -m -- "$@"
  fi
}

dsh_recovery_prepare() {
  local _mode="${1:-recover}" _owner=
  case $DSH_RECOVERY_DIR in
  "$DSH_TMP"/*) ;;
  *)
    dsh_err "unsafe recovery directory: $DSH_RECOVERY_DIR"
    return 1
    ;;
  esac
  if [ -d "$DSH_RECOVERY_DIR" ]; then
    if [ -f "$DSH_RECOVERY_MARKER" ]; then
      _owner=$(dsh_read_line "$DSH_RECOVERY_MARKER" 2>/dev/null || true)
      [ "$_owner" = "$DSH_ROOT" ] || {
        dsh_err "recovery directory belongs to another installation"
        return 1
      }
    elif [ -n "$(ls -A "$DSH_RECOVERY_DIR" 2>/dev/null)" ]; then
      dsh_err "unowned recovery directory: $DSH_RECOVERY_DIR"
      return 1
    fi
  fi
  mkdir -p "$DSH_RECOVERY_DIR" || return 1
  chmod 0700 "$DSH_RECOVERY_DIR" || return 1
  printf '%s\n' "$DSH_ROOT" >"$DSH_RECOVERY_MARKER" || return 1
  chmod 0600 "$DSH_RECOVERY_MARKER" || return 1
  cp "$DSH_MODULE_DIR/bin/dsh-recover-mounts" "$DSH_RECOVERY_SCRIPT.tmp.$$" || return 1
  chmod 0700 "$DSH_RECOVERY_SCRIPT.tmp.$$" || return 1
  mv -f "$DSH_RECOVERY_SCRIPT.tmp.$$" "$DSH_RECOVERY_SCRIPT" || return 1
  cp "$DSH_MODULE_DIR/lib/unmount.sh" "$DSH_RECOVERY_UNMOUNT.tmp.$$" || return 1
  chmod 0600 "$DSH_RECOVERY_UNMOUNT.tmp.$$" || return 1
  mv -f "$DSH_RECOVERY_UNMOUNT.tmp.$$" "$DSH_RECOVERY_UNMOUNT" || return 1
  if [ "$_mode" = fresh ]; then
    rm -f "$DSH_RECOVERY_MOUNTS" "$DSH_RECOVERY_STATUS"
  elif [ -f "$DSH_MOUNTS_FILE" ]; then
    cp "$DSH_MOUNTS_FILE" "$DSH_RECOVERY_MOUNTS" || return 1
  fi
  return 0
}

dsh_recovery_clear() {
  if [ ! -e "$DSH_RECOVERY_DIR" ] && [ ! -L "$DSH_RECOVERY_DIR" ]; then return 0; fi
  [ "$(dsh_read_line "$DSH_RECOVERY_MARKER" 2>/dev/null || true)" = "$DSH_ROOT" ] || {
    dsh_err "refusing to remove an unowned recovery directory"
    return 1
  }
  case $DSH_RECOVERY_DIR in
  "$DSH_TMP"/*) rm -rf "$DSH_RECOVERY_DIR" ;;
  *) return 1 ;;
  esac
}

dsh_recovery_ns_valid() {
  local _ns="$1" _inode _host
  case $_ns in
  mnt:\[*\]) ;;
  *) return 1 ;;
  esac
  _inode=${_ns#mnt:\[}
  _inode=${_inode%\]}
  dsh_is_uint "$_inode" || return 1
  _host=$(dsh_proc_ns "$$" 2>/dev/null || true)
  if [ "$_ns" = "$_host" ] && [ "${DSH_TEST_ALLOW_SAME_NS:-0}" != 1 ]; then
    dsh_err "refusing recovery in the caller mount namespace"
    return 1
  fi
  return 0
}

dsh_recovery_run() {
  local _ns="$1" _anchor _members
  _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null) || return 2
  _anchor=$(printf '%s\n' "$_members" | sed -n '1p')
  dsh_is_uint "$_anchor" || return 2
  dsh_nsenter_any "$_anchor" "$_ns" "$DSH_RECOVERY_SCRIPT" \
    "$DSH_RECOVERY_MOUNTS" "$DSH_ROOTFS_DIR" "$DSH_RECOVERY_STATUS" "$_ns"
}

dsh_recovery_kill_ns() {
  local _ns="$1" _p _members
  [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ] || return 0
  dsh_recovery_ns_valid "$_ns" || return 1
  _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null) || return 1
  for _p in $_members; do
    [ "$_p" = "$$" ] && continue
    kill -TERM "$_p" 2>/dev/null || true
  done
  sleep 1 2>/dev/null || true
  _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null) || return 1
  for _p in $_members; do
    [ "$_p" = "$$" ] && continue
    kill -KILL "$_p" 2>/dev/null || true
  done
  _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null) || return 1
  [ -z "$_members" ]
}

dsh_recover_stale_locked() {
  local _scan_rc=0 _attached _rc=0
  if _attached=$(dsh_proc_any_rootfs "$DSH_ROOTFS_DIR"); then
    dsh_err "stale records cannot be cleared; rootfs is still attached to: $_attached"
    _rc=1
  else
    _scan_rc=$?
    [ "$_scan_rc" -eq 1 ] || {
      dsh_err "cannot verify rootfs mounts; stale records preserved"
      _rc=1
    }
  fi
  if ! _attached=$(dsh_loop_attached_images "$DSH_IMAGE"); then
    dsh_err "cannot verify image loop attachments; stale records preserved"
    _rc=1
  elif [ -n "$_attached" ]; then
    dsh_err "image still has loop attachments; stale records preserved: $_attached"
    _rc=1
  fi
  if [ "$_rc" -ne 0 ]; then
    dsh_state_update state=recovery-required note=unsafe-stale-recovery
    return 4
  fi
  dsh_recovery_prepare fresh || {
    dsh_state_update state=recovery-required note=recovery-prepare-failed
    return 1
  }
  dsh_selinux_restore || _rc=1
  [ "$(dsh_selinux_read || true)" = 1 ] || {
    dsh_err "global SELinux is not enforcing"
    _rc=1
  }
  if [ "$_rc" -eq 0 ]; then
    dsh_clear_runtime_files || _rc=1
    dsh_recovery_clear || _rc=1
  fi
  if [ "$_rc" -ne 0 ]; then
    dsh_state_update state=recovery-required note=stale-cleanup-incomplete
    return 1
  fi
  dsh_state_update state=stopped note=stale-runtime-cleared url= "stopped=$(dsh_timestamp)" || return 1
  dsh_log "stale runtime records cleared without signaling a process"
  return 0
}

dsh_recover_dead_locked() {
  local _ns _loop _anchor _members _rc=0 _cleanup_ok=1
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  _loop=$(dsh_read_line "$DSH_LOOP_FILE" 2>/dev/null || true)
  if [ -n "$_ns" ] && ! dsh_recovery_ns_valid "$_ns"; then
    dsh_state_update state=recovery-required note=invalid-namespace
    return 4
  fi
  if [ -z "$_ns" ] && [ -n "$_loop" ] && [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ]; then
    dsh_err "loop record exists without a namespace; cannot prove mount cleanup"
    dsh_state_update state=recovery-required note=namespace-record-missing
    return 4
  fi
  dsh_recovery_prepare || {
    dsh_state_update state=recovery-required note=recovery-prepare-failed
    return 1
  }
  if [ -n "$_ns" ] && [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ]; then
    _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null) || _rc=1
    if [ "$_rc" -eq 0 ]; then
      _anchor=$(printf '%s\n' "$_members" | sed -n '1p')
      if dsh_is_uint "$_anchor"; then
        dsh_nsenter_any "$_anchor" "$_ns" chroot "$DSH_ROOTFS_DIR" \
          /usr/bin/s6-svscanctl -t /run/s6 >/dev/null 2>&1 || true
        dsh_nsenter_any "$_anchor" "$_ns" sync >/dev/null 2>&1 || true
        dsh_recovery_run "$_ns" || _cleanup_ok=0
        dsh_recovery_kill_ns "$_ns" || _rc=1
        if [ "$_cleanup_ok" -ne 1 ]; then
          dsh_recovery_run "$_ns" || true
        fi
      fi
      if _members=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null); then
        [ -z "$_members" ] || _rc=1
      else
        _rc=1
      fi
    fi
  fi
  if [ "$_cleanup_ok" -ne 1 ]; then
    _rc=1
  fi
  if [ "$_rc" -eq 0 ] && [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ]; then
    _remaining_rc=0
    _attached=$(dsh_proc_any_rootfs "$DSH_ROOTFS_DIR") || _remaining_rc=$?
    case $_remaining_rc in
    0)
      dsh_err "rootfs remains in process namespaces: $_attached"
      _rc=1
      ;;
    2)
      dsh_err "cannot verify rootfs mounts before loop detach"
      _rc=1
      ;;
    esac
  fi
  if [ "$_rc" -eq 0 ]; then
    if [ -n "$_loop" ]; then
      dsh_loop_detach "$_loop" "$DSH_IMAGE" || _rc=1
    fi
    if ! _attached=$(dsh_loop_attached_images "$DSH_IMAGE"); then
      dsh_err "cannot verify remaining loop attachments"
      _rc=1
    elif [ -n "$_attached" ]; then
      dsh_err "refusing to detach unowned loops: $_attached"
      _rc=1
    fi
  fi
  dsh_selinux_restore || _rc=1
  [ "$(dsh_selinux_read || true)" = 1 ] || {
    dsh_err "global SELinux is not enforcing"
    _rc=1
  }
  if [ "$_rc" -eq 0 ]; then
    dsh_clear_runtime_files || _rc=1
    dsh_recovery_clear || _rc=1
  fi
  if [ "$_rc" -ne 0 ]; then
    dsh_state_update state=recovery-required note=cleanup-incomplete
    dsh_err "cleanup incomplete; runtime records preserved for recover"
    return 1
  fi
  dsh_state_update state=stopped note= url= "stopped=$(dsh_timestamp)"
  return 0
}

dsh_start_usage() {
  cat <<'EOF'
Usage: dsh-ctl start [--protect-critical=off] [--permissive] [--apply]

--apply is mandatory in P2; without it the command only prints what would run.
--permissive is an explicit break-glass; stop/watchdog restores the saved state.
EOF
}

dsh_start_attempt() {
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
  if [ -e "$DSH_PID_FILE" ] || [ -e "$DSH_LOOP_FILE" ] || [ -d "$DSH_RECOVERY_DIR" ]; then
    dsh_err "previous runtime records remain; run dsh-ctl recover --apply"
    return 4
  fi

  dsh_doctor_run --for-apply || {
    dsh_state_update state=failed note=doctor-failed
    return 4
  }

  dsh_loop_assert_image_free "$DSH_IMAGE" || {
    dsh_state_update state=failed note=image-already-attached
    return 1
  }
  DSH_START_ACTIVE=1
  if [ "$_permissive" -eq 1 ]; then
    dsh_selinux_save_and_disable || return 1
  fi

  dsh_state_update state=starting note=
  rm -f "$DSH_PID_FILE" "$DSH_STARTTIME_FILE" "$DSH_BOOT_ID_FILE" "$DSH_NS_FILE" "$DSH_URL_FILE" "$DSH_HEALTH_FILE" "$DSH_LOOP_FILE" \
    "$DSH_MOUNTS_FILE" "$DSH_CLEANUP_FILE"
  mkdir -p "$DSH_ROOTFS_DIR" || return 1
  chmod 0700 "$DSH_ROOTFS_DIR" 2>/dev/null || true
  if [ -n "$(ls -A "$DSH_ROOTFS_DIR" 2>/dev/null || true)" ]; then
    dsh_err "rootfs mountpoint is not empty: $DSH_ROOTFS_DIR"
    dsh_state_update state=failed note=mountpoint-not-empty
    return 1
  fi

  dsh_e2fsck_prepare "$DSH_IMAGE" || {
    dsh_state_update state=failed note=e2fsck-failed
    return 1
  }
  if ! dsh_loop_assert_image_free "$DSH_IMAGE"; then
    dsh_recovery_prepare fresh || dsh_warn "cannot create recovery record after an external attachment"
    dsh_state_update state=recovery-required note=image-attached-during-prepare
    return 4
  fi
  dsh_recovery_prepare fresh || {
    dsh_state_update state=failed note=recovery-prepare-failed
    return 1
  }
  _loop=$(dsh_loop_attach "$DSH_IMAGE") || {
    dsh_state_update state=failed note=losetup-failed
    return 1
  }
  printf '%s\n' "$_loop" >"$DSH_LOOP_FILE" || return 1
  dsh_log "image attached: $_loop"

  _unshare=$(dsh_tool_path unshare || true)
  if [ -z "$_unshare" ]; then
    dsh_err "unshare not found"
    dsh_state_update state=failed note=unshare-missing
    return 1
  fi

  _log=$DSH_LOG_DIR/nsinit.log
  mkdir -p "$DSH_LOG_DIR" || true
  (
    DSH_APPLY=1 \
      DSH_LOOP="$_loop" \
      DSH_PROTECT_CRITICAL="$_protect" \
      DSH_SELINUXFS_MASK="$DSH_SELINUXFS_MASK" \
      DSH_SELINUXFS_MODE="$DSH_SELINUXFS_MODE" \
      DSH_BLOCK_ACCESS="$DSH_BLOCK_ACCESS" \
      DSH_ROOT="$DSH_ROOT" \
      DSH_MODULE_DIR="$DSH_MODULE_DIR" \
      DSH_IMAGE="$DSH_IMAGE" \
      DSH_ROOTFS_DIR="$DSH_ROOTFS_DIR" \
      DSH_MEDIA="$DSH_MEDIA" \
      DSH_TMP="$DSH_TMP" \
      DSH_PROC="$DSH_PROC" \
      DSH_SYS="$DSH_SYS" \
      DSH_CONFIG="$DSH_CONFIG" \
      setsid "$_unshare" -m -u "$DSH_NSINIT"
  ) >>"$_log" 2>&1 &
  _launcher=$!
  dsh_log "launcher pid=$_launcher; waiting for holder pid"

  if ! dsh_wait_pid_identity "$DSH_PID_FILE" 20; then
    dsh_err "holder did not publish a valid pid; see $_log"
    dsh_state_update state=failed note=holder-pid-timeout
    return 1
  fi

  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  _pgid=$(dsh_proc_pgid "$_pid" 2>/dev/null || true)
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
      dsh_state_update state=failed note=namespace-not-private
      return 1
    fi
    dsh_warn "test override: holder shares caller mount namespace"
  fi
  dsh_state_update "state=starting" "pid=$_pid" "pgid=$_pgid" "starttime=$_st" "ns=$_ns" "loop=$_loop" "image=$DSH_IMAGE" "version=$DSH_VERSION"

  _ready_rc=0
  dsh_wait_ready "$_pid" "$DSH_URL_FILE" "${DSH_START_TIMEOUT:-90}" || _ready_rc=$?
  if [ "$_ready_rc" -ne 0 ]; then
    if [ "$_ready_rc" -eq 1 ] && dsh_proc_alive "$_pid"; then
      dsh_err "DSH URL not ready; holder kept alive for diagnosis"
      dsh_state_update "state=degraded" "note=url-timeout" "pid=$_pid" "pgid=$_pgid" "starttime=$_st" "ns=$_ns" "loop=$_loop"
    else
      dsh_err "holder exited before URL; cleaning up"
      dsh_state_update state=failed note=holder-exited-before-url
    fi
    return 4
  fi

  _url=$(dsh_read_line "$DSH_URL_FILE" 2>/dev/null || true)
  if ! dsh_url_is_loopback "$_url" || ! dsh_proc_validate_identity "$_pid" "$_st" "$_ns"; then
    dsh_state_update state=failed note=invalid-ready-state
    return 4
  fi
  dsh_state_update "state=running" "pid=$_pid" "pgid=$_pgid" "starttime=$_st" "ns=$_ns" "loop=$_loop" "image=$DSH_IMAGE" "url=$_url" "started=$(dsh_timestamp)" "version=$DSH_VERSION"
  printf '%s\n' "1" >"$DSH_CTL_DIR/autostart" 2>/dev/null || true
  printf '0\n' >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
  rm -f "$DSH_CTL_DIR/boot-rollback-attempted"
  dsh_log "started: holder=$_pid"
  printf 'started: pid=%s\nurl: %s\n' "$_pid" "$_url"
  return 0
}

dsh_start_locked() {
  local _rc _cleanup_rc _note _state
  DSH_START_ACTIVE=0
  dsh_start_attempt "$@"
  _rc=$?
  if [ "$DSH_START_ACTIVE" = 1 ] && [ "$_rc" -ne 0 ]; then
    _state=$(dsh_state_get state 2>/dev/null || true)
    if [ "$_state" = degraded ]; then
      if ! dsh_selinux_restore || [ "$(dsh_selinux_read || true)" != 1 ]; then
        dsh_state_update state=recovery-required note=selinux-restore-failed
        return 4
      fi
      DSH_START_ACTIVE=0
      return "$_rc"
    fi
    _note=$(dsh_state_get note 2>/dev/null || true)
    dsh_stop_locked
    _cleanup_rc=$?
    if [ "$_cleanup_rc" -ne 0 ]; then
      dsh_state_update state=recovery-required note=start-cleanup-failed
      return 4
    fi
    dsh_state_update state=failed "note=${_note:-start-failed}"
  fi
  DSH_START_ACTIVE=0
  return "$_rc"
}

dsh_start() {
  local _arg _rc
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" != 1 ]; then
    for _arg in "$@"; do
      [ "$_arg" = --apply ] || continue
      dsh_image_claim_exec start "$@"
      return $?
    done
  fi
  dsh_lock_acquire || return 1
  dsh_start_locked "$@"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_stop_locked() {
  local _pid _st _ns _svc _i
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)

  if ! dsh_is_uint "$_pid" || ! dsh_proc_alive "$_pid"; then
    dsh_recover_dead_locked
    return $?
  fi
  if ! dsh_proc_validate_identity "$_pid" "$_st" "$_ns"; then
    dsh_err "holder identity mismatch (pid reuse?); refusing to signal pid $_pid"
    dsh_state_update state=stale note=identity-mismatch
    return 4
  fi
  dsh_recovery_ns_valid "$_ns" || {
    dsh_state_update state=recovery-required note=invalid-namespace
    return 4
  }

  if [ -f "$DSH_CLEANUP_FILE" ]; then
    dsh_log "cleanup already in progress; waiting for holder"
    _i=0
    while [ "$_i" -lt "${DSH_CLEANUP_TIMEOUT:-60}" ] && dsh_proc_alive "$_pid"; do
      sleep 1
      _i=$((_i + 1))
    done
    if dsh_proc_alive "$_pid"; then
      dsh_state_update state=stopping note=cleanup-timeout
      dsh_err "holder cleanup did not finish; refusing to interrupt it"
      return 4
    fi
    dsh_recover_dead_locked || return 1
    dsh_log "stopped"
    return 0
  fi

  dsh_state_update "state=stopping" "pid=$_pid" "starttime=$_st" "ns=$_ns"
  dsh_guest_shell "$_pid" 'sync; sync' 2>/dev/null || dsh_warn "guest sync failed"
  for _svc in dsh ttyd; do
    dsh_guest_run "$_pid" /usr/bin/s6-svc -d "/run/s6/$_svc" 2>/dev/null || true
    dsh_guest_run "$_pid" /usr/bin/s6-svwait -d -t 10000 "/run/s6/$_svc" 2>/dev/null || true
  done
  dsh_guest_run "$_pid" /usr/bin/s6-svscanctl -t /run/s6 2>/dev/null || true

  kill -TERM "$_pid" 2>/dev/null || true
  _i=0
  while [ "$_i" -lt "${DSH_STOP_TIMEOUT:-30}" ] && dsh_proc_alive "$_pid"; do
    sleep 1
    _i=$((_i + 1))
  done
  if dsh_proc_alive "$_pid"; then
    dsh_warn "holder did not stop cleanly; forcing the s6 supervision tree down"
    for _svc in dsh ttyd; do
      dsh_guest_run "$_pid" /usr/bin/s6-svc -k "/run/s6/$_svc" 2>/dev/null || true
    done
    dsh_guest_run "$_pid" /usr/bin/s6-svscanctl -q /run/s6 2>/dev/null || true
    kill -TERM "$_pid" 2>/dev/null || true
    _i=0
    while [ "$_i" -lt 10 ] && dsh_proc_alive "$_pid"; do
      sleep 1
      _i=$((_i + 1))
    done
  fi
  if dsh_proc_alive "$_pid"; then
    dsh_state_update state=stopping note=holder-stop-timeout
    dsh_err "holder is still alive; run dsh-ctl recover --apply for forced recovery"
    return 4
  fi
  dsh_recover_dead_locked || return 1
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

dsh_boot_recover_previous() {
  local _recorded _current _rc
  [ -e "$DSH_PID_FILE" ] || [ -e "$DSH_LOOP_FILE" ] || [ -d "$DSH_RECOVERY_DIR" ] || return 0
  _recorded=$(dsh_read_line "$DSH_BOOT_ID_FILE" 2>/dev/null || true)
  _current=$(dsh_boot_id || true)
  if [ -z "$_recorded" ] || [ -z "$_current" ] || [ "$_recorded" = "$_current" ]; then
    return 2
  fi
  dsh_warn "boot: previous-boot runtime records found; verifying safe stale cleanup"
  dsh_lock_acquire || return 1
  dsh_recover_stale_locked
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_boot() {
  _timeout=${DSH_BOOT_TIMEOUT:-300}
  _i=0
  while [ "$_i" -lt "$_timeout" ]; do
    _boot=$(getprop sys.boot_completed 2>/dev/null || true)
    _ce=$(getprop sys.user.0.ce_available 2>/dev/null || true)
    [ "$_boot" = 1 ] && dsh_bool "$_ce" && break
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
  if [ "$_state" = rescue-needed ]; then
    dsh_log "boot: automatic start suppressed by $_state"
    return 1
  fi
  dsh_boot_recover_previous
  _boot_recovery_rc=$?
  [ "$_boot_recovery_rc" -ne 1 ] || return 1
  if [ "$_state" = recovery-required ] && [ "$_boot_recovery_rc" -ne 0 ]; then
    dsh_log "boot: automatic start suppressed by unverified runtime records"
    return 1
  fi
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  if dsh_is_uint "$_pid" && dsh_proc_validate_identity "$_pid" "$_st" "$_ns"; then
    dsh_log "boot: holder already active; not starting a second instance"
    return 0
  fi
  if dsh_start --apply; then
    printf '0\n' >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
    return 0
  fi
  if [ "$(dsh_state_get note 2>/dev/null || true)" = e2fsck-failed ] &&
    [ -s "$DSH_LAST_GOOD_FILE" ] && [ ! -e "$DSH_CTL_DIR/boot-rollback-attempted" ]; then
    printf '1\n' >"$DSH_CTL_DIR/boot-rollback-attempted"
    dsh_warn "boot: attempting one verified last-good restore"
    if dsh_snapshot_restore_last_good && dsh_start --apply; then
      printf '0\n' >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
      rm -f "$DSH_CTL_DIR/boot-rollback-attempted"
      return 0
    fi
  fi
  _fails=$(dsh_read_line "$DSH_CTL_DIR/boot-fails" 2>/dev/null || true)
  dsh_is_uint "$_fails" || _fails=0
  _fails=$((_fails + 1))
  printf '%s\n' "$_fails" >"$DSH_CTL_DIR/boot-fails" 2>/dev/null || true
  dsh_err "boot start failed (${_fails}/3)"
  if [ "$_fails" -ge 3 ]; then
    dsh_state_update state=rescue-needed note=boot-failures
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
  for _f in "$DSH_LOG_DIR/dsh-ctl.log" "$DSH_LOG_DIR/nsinit.log" "$DSH_LOG_DIR/boot.log" "$DSH_LOG_DIR/watchdog.log"; do
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
  local _arg
  for _arg in "$@"; do
    [ "$_arg" = --apply ] || continue
    dsh_image_claim_exec rescue "$@"
    return $?
  done
  dsh_rescue_claimed "$@"
}

dsh_rescue_claimed() {
  local _apply=0 _arg _loop _unshare _rc _cleanup_rc
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
  dsh_stop || {
    dsh_err "rescue refuses to inspect the image until stop/recovery succeeds"
    return 1
  }
  dsh_lock_acquire || return 1
  dsh_recovery_prepare fresh || {
    dsh_lock_release
    return 1
  }
  dsh_e2fsck_prepare "$DSH_IMAGE" || {
    dsh_recovery_clear || true
    dsh_lock_release
    return 1
  }
  _loop=$(dsh_loop_attach "$DSH_IMAGE") || {
    dsh_recover_dead_locked || true
    dsh_lock_release
    return 1
  }
  printf '%s\n' "$_loop" >"$DSH_LOOP_FILE" || {
    dsh_loop_detach "$_loop" "$DSH_IMAGE" || true
    dsh_lock_release
    return 1
  }
  _unshare=$(dsh_tool_path unshare || true)
  [ -n "$_unshare" ] || {
    dsh_err "unshare not found"
    dsh_recover_dead_locked || true
    dsh_lock_release
    return 1
  }
  dsh_state_update state=rescue note=
  printf 'rescue: rootfs is read-only; no /data/storage; exit bash to clean up\n' >&2
  (
    DSH_RESCUE=1 DSH_APPLY=1 DSH_LOOP="$_loop" \
      DSH_ROOT="$DSH_ROOT" DSH_MODULE_DIR="$DSH_MODULE_DIR" \
      DSH_IMAGE="$DSH_IMAGE" DSH_ROOTFS_DIR="$DSH_ROOTFS_DIR" \
      DSH_MEDIA="$DSH_MEDIA" DSH_TMP="$DSH_TMP" \
      DSH_PROC="$DSH_PROC" DSH_SYS="$DSH_SYS" DSH_CONFIG="$DSH_CONFIG" \
      "$_unshare" -m -u "$DSH_NSINIT"
  )
  _rc=$?
  dsh_recover_dead_locked
  _cleanup_rc=$?
  dsh_lock_release
  [ "$_cleanup_rc" -eq 0 ] || return 1
  return "$_rc"
}

dsh_autostart() {
  _op=${1:-status}
  case $_op in
  status)
    _v=$(dsh_read_line "$DSH_CTL_DIR/autostart" 2>/dev/null || true)
    [ "$_v" = 1 ] && printf 'autostart: on\n' || printf 'autostart: off\n'
    ;;
  on)
    dsh_state_ensure || return 1
    printf '1\n' >"$DSH_CTL_DIR/autostart" || return 1
    printf 'autostart: on\n'
    ;;
  off)
    dsh_state_ensure || return 1
    printf '0\n' >"$DSH_CTL_DIR/autostart" || return 1
    printf 'autostart: off\n'
    ;;
  -h | --help)
    printf 'Usage: dsh-ctl autostart status|on|off\n'
    ;;
  *)
    dsh_err "unknown autostart operation: $_op"
    return 2
    ;;
  esac
}

dsh_recover() {
  local _apply=0 _arg _pid _st _ns _svc _i _rc
  for _arg in "$@"; do
    case $_arg in
    --apply) _apply=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl recover --apply\n'
      return 0
      ;;
    *)
      dsh_err "unknown recover option: $_arg"
      return 2
      ;;
    esac
  done
  if [ "$_apply" -ne 1 ]; then
    dsh_err "recover requires the explicit --apply flag"
    printf 'recover: force-stop a stuck holder, clean its namespace, and detach the owned loop\n'
    return 3
  fi
  dsh_lock_acquire || return 1
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  if dsh_is_uint "$_pid" && dsh_proc_alive "$_pid"; then
    if ! dsh_proc_validate_identity "$_pid" "$_st" "$_ns"; then
      dsh_warn "holder identity mismatch; checking stale records without signaling pid $_pid"
      dsh_recover_stale_locked
      _rc=$?
      dsh_lock_release
      printf 'recover: %s\n' "$([ "$_rc" -eq 0 ] && printf ok || printf incomplete)"
      return "$_rc"
    fi
    dsh_state_update state=stopping note=forced-recovery
    dsh_guest_shell "$_pid" 'sync; sync' 2>/dev/null || true
    for _svc in dsh ttyd; do
      dsh_guest_run "$_pid" /usr/bin/s6-svc -k "/run/s6/$_svc" 2>/dev/null || true
    done
    dsh_guest_run "$_pid" /usr/bin/s6-svscanctl -q /run/s6 2>/dev/null || true
    kill -TERM "$_pid" 2>/dev/null || true
    _i=0
    while [ "$_i" -lt 5 ] && dsh_proc_alive "$_pid"; do
      sleep 1
      _i=$((_i + 1))
    done
    if dsh_proc_alive "$_pid"; then
      kill -KILL "$_pid" 2>/dev/null || true
      _i=0
      while [ "$_i" -lt 5 ] && dsh_proc_alive "$_pid"; do
        sleep 1
        _i=$((_i + 1))
      done
    fi
    if dsh_proc_alive "$_pid"; then
      dsh_state_update state=recovery-required note=holder-kill-failed
      dsh_lock_release
      return 1
    fi
  fi
  dsh_recover_dead_locked
  _rc=$?
  dsh_lock_release
  printf 'recover: %s\n' "$([ "$_rc" -eq 0 ] && printf ok || printf incomplete)"
  return "$_rc"
}
