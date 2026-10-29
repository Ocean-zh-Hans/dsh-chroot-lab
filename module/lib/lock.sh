# shellcheck shell=sh disable=SC3043
# Atomic mkdir lock. Never uses toybox flock file descriptors.

dsh_lifecycle_exec() {
  [ "$#" -gt 0 ] || return 2
  if [ "${DSH_LIFECYCLE_CLAIM_ACTIVE:-0}" = 1 ]; then
    "${DSH_TEST_SHELL:-/system/bin/sh}" "$@"
    return $?
  fi
  _helper=$(dsh_tool_path dsh-mount-ro-recursive || true)
  [ -n "$_helper" ] || {
    dsh_err "lifecycle claim helper is missing"
    return 1
  }
  dsh_state_ensure || return 1
  local _lifecycle_timeout
  _lifecycle_timeout=${DSH_LIFECYCLE_CLAIM_TIMEOUT:-120}
  case $_lifecycle_timeout in
  '' | *[!0-9]*) return 2 ;;
  esac
  DSH_LIFECYCLE_CLAIM_ACTIVE=1 "$_helper" --claim-wait "$_lifecycle_timeout" \
    "$DSH_CTL_DIR/lifecycle.claim" "${DSH_TEST_SHELL:-/system/bin/sh}" "$@"
}

dsh_lock_path_safe() {
  case $DSH_LOCK_DIR in
  "$DSH_CTL_DIR"/*) return 0 ;;
  *)
    dsh_err "refusing lock path outside control dir: $DSH_LOCK_DIR"
    return 1
    ;;
  esac
}

dsh_lock_is_stale() {
  _pid=$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)
  _st=$(dsh_read_line "$DSH_LOCK_DIR/starttime" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_LOCK_DIR/ns" 2>/dev/null || true)
  if ! dsh_is_uint "$_pid"; then
    return 0
  fi
  _lock_stat=$(dsh_proc_stat_file "$_pid")
  [ -e "$_lock_stat" ] || return 0
  [ -r "$_lock_stat" ] || return 2
  _lock_stat_line=$(dsh_read_line "$_lock_stat" 2>/dev/null) || return 2
  _lock_rest=${_lock_stat_line##*) }
  [ "$_lock_rest" != "$_lock_stat_line" ] || return 2
  _lock_state=${_lock_rest%% *}
  case $_lock_state in
  Z | X | x) return 0 ;;
  '' | *[!A-Za-z]*) return 2 ;;
  esac
  if [ -n "$_st" ] && [ -n "$_ns" ]; then
    _actual_st=$(dsh_proc_starttime "$_pid") || return 2
    _actual_ns=$(dsh_proc_ns "$_pid") || return 2
    [ "$_actual_st" = "$_st" ] || return 0
    [ "$_actual_ns" = "$_ns" ] || return 0
  fi
  if [ -f "$DSH_BOOT_ID_FILE" ]; then
    _recorded_boot=$(dsh_read_line "$DSH_BOOT_ID_FILE" 2>/dev/null || true)
    _current_boot=$(dsh_boot_id || true)
    [ -n "$_recorded_boot" ] && [ -n "$_current_boot" ] || return 2
    [ "$_recorded_boot" = "$_current_boot" ] || return 0
  fi
  return 1
}

dsh_lock_clear_stale() {
  dsh_lock_path_safe || return 1
  _stale_pid=$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)
  _stale_st=$(dsh_read_line "$DSH_LOCK_DIR/starttime" 2>/dev/null || true)
  _stale_ns=$(dsh_read_line "$DSH_LOCK_DIR/ns" 2>/dev/null || true)
  _quarantine=$DSH_LOCK_DIR.stale.$$
  case $_quarantine in
  "$DSH_CTL_DIR"/*) ;;
  *) return 1 ;;
  esac
  [ ! -e "$_quarantine" ] || return 1
  mv "$DSH_LOCK_DIR" "$_quarantine" || return 1
  if [ "$(dsh_read_line "$_quarantine/pid" 2>/dev/null || true)" != "$_stale_pid" ] ||
    [ "$(dsh_read_line "$_quarantine/starttime" 2>/dev/null || true)" != "$_stale_st" ] ||
    [ "$(dsh_read_line "$_quarantine/ns" 2>/dev/null || true)" != "$_stale_ns" ]; then
    if [ ! -e "$DSH_LOCK_DIR" ]; then
      mv "$_quarantine" "$DSH_LOCK_DIR" || return 1
    fi
    dsh_err "lock owner changed during stale cleanup"
    return 1
  fi
  rm -rf "$_quarantine"
}

dsh_lock_acquire() {
  dsh_state_ensure || return 1
  if [ "${DSH_LOCK_GUARD_ACTIVE:-0}" != 1 ]; then
    _lock_helper=$(dsh_tool_path dsh-mount-ro-recursive || true)
    [ -n "$_lock_helper" ] || {
      dsh_err "lock claim helper is missing"
      return 1
    }
    [ -x "$DSH_MODULE_DIR/bin/dsh-lock" ] || {
      dsh_err "lock entrypoint is missing: $DSH_MODULE_DIR/bin/dsh-lock"
      return 1
    }
    DSH_LOCK_OWNER_PID=$$ DSH_LOCK_GUARD_ACTIVE=1 "$_lock_helper" --claim "$DSH_CTL_DIR/lock.guard" \
      "${DSH_TEST_SHELL:-/system/bin/sh}" "$DSH_MODULE_DIR/bin/dsh-lock"
    return $?
  fi
  _lock_owner_pid=${DSH_LOCK_OWNER_PID:-$$}
  _candidate=$DSH_LOCK_DIR.candidate.$$
  case $_candidate in
  "$DSH_CTL_DIR"/*) ;;
  *)
    dsh_err "unsafe lock candidate: $_candidate"
    return 1
    ;;
  esac
  rm -rf "$_candidate" || return 1
  mkdir "$_candidate" || return 1
  printf '%s\n' "$_lock_owner_pid" >"$_candidate/pid" || return 1
  dsh_proc_starttime "$_lock_owner_pid" >"$_candidate/starttime" 2>/dev/null || true
  dsh_proc_ns "$_lock_owner_pid" >"$_candidate/ns" 2>/dev/null || true
  _attempt=0
  while [ "$_attempt" -lt 3 ]; do
    if [ -d "$DSH_LOCK_DIR" ] && dsh_lock_is_stale; then
      dsh_lock_clear_stale || {
        rm -rf "$_candidate"
        dsh_err "cannot quarantine a stale lock: $DSH_LOCK_DIR"
        return 1
      }
    fi
    if mkdir "$DSH_LOCK_DIR" 2>/dev/null; then
      if cp "$_candidate/pid" "$DSH_LOCK_DIR/pid" &&
        cp "$_candidate/starttime" "$DSH_LOCK_DIR/starttime" &&
        cp "$_candidate/ns" "$DSH_LOCK_DIR/ns" &&
        [ "$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)" = "$_lock_owner_pid" ]; then
        rm -rf "$_candidate"
        return 0
      fi
      rm -rf "$DSH_LOCK_DIR"
      dsh_err "cannot initialize lock owner metadata: $DSH_LOCK_DIR"
      return 1
    fi
    _attempt=$((_attempt + 1))
  done
  rm -rf "$_candidate"
  dsh_err "lock is held or cannot be acquired: $DSH_LOCK_DIR"
  return 1
}

dsh_lock_release() {
  dsh_lock_path_safe || return 1
  _owner=$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)
  [ "$_owner" = "$$" ] || return 0
  _released=$DSH_LOCK_DIR.release.$$
  case $_released in
  "$DSH_CTL_DIR"/*) ;;
  *) return 1 ;;
  esac
  [ ! -e "$_released" ] || return 1
  mv "$DSH_LOCK_DIR" "$_released" || return 1
  rm -rf "$_released"
}
