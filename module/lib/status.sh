# shellcheck shell=sh disable=SC3043
# Read-only status. Never signals or cleans up.

dsh_status_file_value() {
  _key=$1
  [ -r "$DSH_STATUS_FILE" ] || return 1
  grep "^${_key}=" "$DSH_STATUS_FILE" 2>/dev/null | tail -n 1 | sed "s/^${_key}=//"
}

dsh_status_run() {
  _json=0
  for _arg in "$@"; do
    case $_arg in
    --json) _json=1 ;;
    -h | --help)
      dsh_status_help
      return 0
      ;;
    *)
      dsh_err "unknown status option: $_arg"
      return 2
      ;;
    esac
  done

  _state=$(dsh_status_file_value state 2>/dev/null || true)
  _status_note=$(dsh_status_file_value note 2>/dev/null || true)
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  _recorded_st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _recorded_ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  _loop=$(dsh_read_line "$DSH_LOOP_FILE" 2>/dev/null || true)
  _last_good=$(dsh_read_line "$DSH_LAST_GOOD_FILE" 2>/dev/null || true)
  _url=$(dsh_read_line "$DSH_URL_FILE" 2>/dev/null || true)
  [ -n "$_url" ] || _url=$(dsh_status_file_value url 2>/dev/null || true)
  _started=$(dsh_status_file_value started 2>/dev/null || true)

  _pid_valid=0
  _actual_st=''
  _actual_ns=''
  if dsh_is_uint "$_pid" && dsh_proc_alive "$_pid"; then
    _actual_st=$(dsh_proc_starttime "$_pid" 2>/dev/null || true)
    _actual_ns=$(dsh_proc_ns "$_pid" 2>/dev/null || true)
    if dsh_proc_validate_identity "$_pid" "$_recorded_st" "$_recorded_ns"; then
      _pid_valid=1
    fi
  fi

  if [ "$_pid_valid" -eq 1 ]; then
    _state=running
  elif [ -n "$_pid" ]; then
    _state=stale
  elif [ "$_state" = degraded ]; then
    _state=degraded
  elif [ "$_state" = running ]; then
    _state=stale
  elif [ -z "$_state" ]; then
    _state=unknown
  fi

  _image_present=0
  [ -f "$DSH_IMAGE" ] && _image_present=1

  if [ "$_json" -eq 1 ]; then
    printf '{'
    printf '"state":"%s",' "$(dsh_json_escape "$_state")"
    if dsh_is_uint "$_pid"; then
      printf '"pid":%s,' "$_pid"
    else
      printf '"pid":null,'
    fi
    printf '"starttime":"%s",' "$(dsh_json_escape "$_recorded_st")"
    printf '"ns":"%s",' "$(dsh_json_escape "$_recorded_ns")"
    printf '"pid_valid":%s,' "$([ "$_pid_valid" -eq 1 ] && printf true || printf false)"
    printf '"loop":"%s",' "$(dsh_json_escape "$_loop")"
    printf '"image":"%s",' "$(dsh_json_escape "$DSH_IMAGE")"
    printf '"image_present":%s,' "$([ "$_image_present" -eq 1 ] && printf true || printf false)"
    printf '"url":"%s",' "$(dsh_json_escape "$_url")"
    printf '"started":"%s",' "$(dsh_json_escape "$_started")"
    printf '"last_good":"%s"' "$(dsh_json_escape "$_last_good")"
    printf '}\n'
    return 0
  fi

  printf 'DSH Ubuntu Chroot status %s\n' "$DSH_VERSION"
  printf 'state        : %s\n' "$_state"
  printf 'pid          : %s\n' "${_pid:-none}"
  printf 'starttime    : %s\n' "${_recorded_st:-none}"
  printf 'ns           : %s\n' "${_recorded_ns:-none}"
  printf 'pid valid    : %s\n' "$([ "$_pid_valid" -eq 1 ] && printf yes || printf no)"
  if [ -n "$_actual_st" ]; then
    printf 'actual start : %s\n' "$_actual_st"
  fi
  if [ -n "$_actual_ns" ]; then
    printf 'actual ns    : %s\n' "$_actual_ns"
  fi
  printf 'loop         : %s\n' "${_loop:-none}"
  printf 'image        : %s (%s)\n' "$DSH_IMAGE" "$([ "$_image_present" -eq 1 ] && printf present || printf missing)"
  printf 'url          : %s\n' "${_url:-none}"
  printf 'started      : %s\n' "${_started:-none}"
  printf 'last-good    : %s\n' "${_last_good:-none}"
  [ -n "$_status_note" ] && printf 'note         : %s\n' "$_status_note"
  return 0
}

dsh_status_help() {
  cat <<'EOF'
Usage: dsh-ctl status [--json]

Read-only status. Validates holder PID/starttime/mount-namespace if present.
Never signals, unmounts, or detaches anything.
EOF
}
