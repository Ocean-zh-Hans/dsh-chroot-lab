# shellcheck shell=sh disable=SC3043
# Atomic state helpers. All writes stay under DSH_ROOT/state.

dsh_state_ensure() {
  [ "$DSH_ROOT" != / ] && [ "$DSH_STATE_DIR" != / ] || return 1
  mkdir -p "$DSH_CTL_DIR" "$DSH_GUEST_DIR" "$DSH_GUEST_RUNTIME_DIR" "$DSH_LOG_DIR" || return 1
  chmod 0700 "$DSH_ROOT" "$DSH_STATE_DIR" "$DSH_CTL_DIR" "$DSH_GUEST_DIR" "$DSH_GUEST_RUNTIME_DIR" "$DSH_LOG_DIR" || return 1
  for _state_name in url health guard ttyd.pass; do
    if [ ! -e "$DSH_GUEST_RUNTIME_DIR/$_state_name" ] && [ -e "$DSH_GUEST_DIR/$_state_name" ]; then
      mv "$DSH_GUEST_DIR/$_state_name" "$DSH_GUEST_RUNTIME_DIR/$_state_name" || return 1
    fi
  done
}

dsh_state_write_atomic() {
  _path=$1
  shift
  _dir=$(dirname "$_path")
  mkdir -p "$_dir" || return 1
  _tmp=$_path.tmp.$$
  : >"$_tmp" || return 1
  for _line in "$@"; do
    printf '%s\n' "$_line" >>"$_tmp" || {
      rm -f "$_tmp"
      return 1
    }
  done
  sync 2>/dev/null || true
  chmod 0600 "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  mv -f "$_tmp" "$_path" || {
    rm -f "$_tmp"
    return 1
  }
}

dsh_state_set() {
  # Args: key=value...
  dsh_state_write_atomic "$DSH_STATUS_FILE" "$@" || return 1
}

dsh_state_update() {
  # Preserve existing keys, replace/add the provided key=value pairs.
  _tmp=$DSH_STATUS_FILE.tmp.$$
  : >"$_tmp" || return 1
  if [ -r "$DSH_STATUS_FILE" ]; then
    while IFS= read -r _line || [ -n "${_line:-}" ]; do
      case $_line in
      '' | '#'*) continue ;;
      esac
      _key=${_line%%=*}
      _replace=0
      for _kv in "$@"; do
        [ "${_kv%%=*}" = "$_key" ] && _replace=1
      done
      [ "$_replace" -eq 0 ] && printf '%s\n' "$_line" >>"$_tmp"
    done <"$DSH_STATUS_FILE"
  fi
  for _kv in "$@"; do printf '%s\n' "$_kv" >>"$_tmp"; done
  sync 2>/dev/null || true
  chmod 0600 "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  mv -f "$_tmp" "$DSH_STATUS_FILE"
}

dsh_state_get() {
  dsh_status_file_value "$1"
}

dsh_write_pid_identity() {
  _pid=$1
  _st=$(dsh_proc_starttime "$_pid") || return 1
  _ns=$(dsh_proc_ns "$_pid") || return 1
  dsh_state_write_atomic "$DSH_STARTTIME_FILE" "$_st" || return 1
  dsh_state_write_atomic "$DSH_NS_FILE" "$_ns" || return 1
}

dsh_write_boot_identity() {
  _boot_id=$(dsh_boot_id) || return 1
  [ -n "$_boot_id" ] || return 1
  dsh_state_write_atomic "$DSH_BOOT_ID_FILE" "$_boot_id"
}

dsh_clear_runtime_files() {
  rm -f "$DSH_PID_FILE" "$DSH_STARTTIME_FILE" "$DSH_BOOT_ID_FILE" "$DSH_NS_FILE" "$DSH_LOOP_FILE" "$DSH_URL_FILE" \
    "$DSH_HEALTH_FILE" "$DSH_MOUNTS_FILE" "$DSH_CLEANUP_FILE" \
    "$DSH_MOUNTS_FILE".reverse.* "$DSH_MOUNTS_FILE".tree.*
}

dsh_wait_ready() {
  local _pid="$1" _url="$2" _timeout="${3:-90}" _i=0
  while [ "$_i" -lt "$_timeout" ]; do
    [ -s "$_url" ] && return 0
    [ ! -e "$DSH_CLEANUP_FILE" ] || return 2
    dsh_proc_alive "$_pid" || return 3
    sleep 1
    _i=$((_i + 1))
  done
  return 1
}

dsh_wait_file() {
  _path=$1
  _timeout=${2:-30}
  _i=0
  while [ "$_i" -lt "$_timeout" ]; do
    [ -s "$_path" ] && return 0
    sleep 1
    _i=$((_i + 1))
  done
  return 1
}

dsh_wait_pid_identity() {
  _path=$1
  _timeout=${2:-15}
  _i=0
  while [ "$_i" -lt "$_timeout" ]; do
    if [ -s "$_path" ]; then
      _pid=$(dsh_read_line "$_path" 2>/dev/null || true)
      if dsh_is_uint "$_pid" && dsh_proc_alive "$_pid"; then
        dsh_write_pid_identity "$_pid" && return 0
      fi
    fi
    sleep 1
    _i=$((_i + 1))
  done
  return 1
}
