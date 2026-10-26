# shellcheck shell=sh disable=SC3043
# Atomic mkdir lock. Never uses toybox flock file descriptors.

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
  if ! dsh_proc_alive "$_pid"; then
    return 0
  fi
  if [ -n "$_st" ] && [ -n "$_ns" ]; then
    dsh_proc_validate_identity "$_pid" "$_st" "$_ns" || return 0
  fi
  return 1
}

dsh_lock_clear_stale() {
  dsh_lock_path_safe || return 1
  rm -f "$DSH_LOCK_DIR/pid" "$DSH_LOCK_DIR/starttime" "$DSH_LOCK_DIR/ns" 2>/dev/null || true
  rmdir "$DSH_LOCK_DIR" 2>/dev/null || return 1
}

dsh_lock_acquire() {
  dsh_state_ensure || return 1
  _tries=0
  while ! mkdir "$DSH_LOCK_DIR" 2>/dev/null; do
    _tries=$((_tries + 1))
    if [ "$_tries" -gt 3 ]; then
      dsh_err "lock is held: $DSH_LOCK_DIR"
      return 1
    fi
    if dsh_lock_is_stale; then
      dsh_warn "removing stale lock: $DSH_LOCK_DIR"
      dsh_lock_clear_stale || {
        dsh_err "cannot remove stale lock: $DSH_LOCK_DIR"
        return 1
      }
    else
      _owner=$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)
      dsh_err "lock is held by pid ${_owner:-unknown}: $DSH_LOCK_DIR"
      return 1
    fi
  done
  printf '%s\n' "$$" >"$DSH_LOCK_DIR/pid" || return 1
  dsh_proc_starttime "$$" >"$DSH_LOCK_DIR/starttime" 2>/dev/null || true
  dsh_proc_ns "$$" >"$DSH_LOCK_DIR/ns" 2>/dev/null || true
  return 0
}

dsh_lock_release() {
  dsh_lock_path_safe || return 1
  _owner=$(dsh_read_line "$DSH_LOCK_DIR/pid" 2>/dev/null || true)
  [ "$_owner" = "$$" ] || return 0
  rm -f "$DSH_LOCK_DIR/pid" "$DSH_LOCK_DIR/starttime" "$DSH_LOCK_DIR/ns" 2>/dev/null || true
  rmdir "$DSH_LOCK_DIR" 2>/dev/null || true
}
