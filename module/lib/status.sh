# shellcheck shell=sh disable=SC3043
# Read-only status. Never signals or cleans up.

dsh_status_file_value() {
  _key=$1
  [ -r "$DSH_STATUS_FILE" ] || return 1
  grep "^${_key}=" "$DSH_STATUS_FILE" 2>/dev/null | tail -n 1 | sed "s/^${_key}=//"
}

dsh_status_health_fresh() {
  _health=$(dsh_read_line "$DSH_HEALTH_FILE" 2>/dev/null || true)
  _updated=${_health#ok:}
  [ "$_updated" != "$_health" ] || return 1
  dsh_is_uint "$_updated" || return 1
  _now=$(date +%s 2>/dev/null || true)
  dsh_is_uint "$_now" || return 1
  _age=$((_now - _updated))
  _max=${DSH_HEALTH_MAX_AGE:-60}
  case $_max in
  '' | *[!0-9]*) _max=60 ;;
  esac
  [ "$_age" -ge -5 ] && [ "$_age" -le "$_max" ]
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
  _purge_in_progress=0
  [ ! -e "$DSH_PURGE_MARKER" ] || _purge_in_progress=1
  _watchdog_disabled=0
  [ ! -e "$DSH_WATCHDOG_DISABLED" ] || _watchdog_disabled=1
  [ "$_purge_in_progress" -eq 0 ] || _watchdog_disabled=1
  _watchdog_label=enabled
  [ "$_watchdog_disabled" -eq 0 ] || _watchdog_label=disabled
  [ "$_purge_in_progress" -eq 0 ] || _watchdog_label=purge-in-progress
  _residual_resources=0
  _loop_check=unknown
  _attached_loops=$(dsh_loop_attached_images "$DSH_IMAGE" 2>/dev/null) && _loop_check=ok
  _unexpected_loops=''
  if [ "$_loop_check" = ok ]; then
    _unexpected_loops=$(dsh_loop_unexpected_images "$_loop" "$DSH_IMAGE" 2>/dev/null) || _loop_check=error
  fi
  _rootfs_mounted=0
  mountpoint -q "$DSH_ROOTFS_DIR" 2>/dev/null && _rootfs_mounted=1
  if [ -n "$_loop" ] || [ -n "$_attached_loops" ] || [ "$_rootfs_mounted" -eq 1 ]; then
    _residual_resources=1
  fi

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

  _private_mount_check=skipped
  _private_mounts=''
  if [ "$_pid_valid" -eq 0 ]; then
    _private_mount_check=0
    _private_mounts=$(dsh_proc_any_rootfs "$DSH_ROOTFS_DIR" 2>/dev/null) || _private_mount_check=$?
    case $_private_mount_check in
    0 | 2) _residual_resources=1 ;;
    esac
  fi

  _image_present=0
  [ -f "$DSH_IMAGE" ] && _image_present=1
  if [ "$_pid_valid" -eq 1 ]; then
    _residual_resources=0
    case $_state in
    starting | stopping | recovery-required | rescue) ;;
    running | degraded)
      if [ "$_image_present" -eq 0 ]; then
        _state=degraded
        _status_note=image-missing
      elif [ -z "$_url" ] || ! dsh_status_health_fresh || [ "$_loop_check" != ok ] || [ -n "$_unexpected_loops" ]; then
        _state=degraded
      elif [ -n "$_loop" ] && ! dsh_loop_matches_image "$_loop" "$DSH_IMAGE"; then
        _state=degraded
      else
        _state=running
      fi
      ;;
    *) _state=degraded ;;
    esac
  else
    if [ -n "$_pid" ]; then
      case $_state in
      recovery-required | stopping) ;;
      *) _state=stale ;;
      esac
    elif [ "$_state" = running ]; then
      _state=stale
    elif [ -z "$_state" ]; then
      _state=unknown
    fi
    if [ "$_residual_resources" -eq 1 ] || [ "$_loop_check" != ok ]; then
      case $_state in
      stopping | recovery-required) ;;
      *) _state=recovery-required ;;
      esac
    fi
  fi

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
    printf '"note":"%s",' "$(dsh_json_escape "$_status_note")"
    printf '"last_good":"%s",' "$(dsh_json_escape "$_last_good")"
    printf '"loop_query":"%s",' "$_loop_check"
    printf '"rootfs_mounted":%s,' "$([ "$_rootfs_mounted" -eq 1 ] && printf true || printf false)"
    printf '"private_mount_check":"%s",' "$_private_mount_check"
    printf '"rootfs_namespace_pids":"%s",' "$(dsh_json_escape "$_private_mounts")"
    printf '"residual_resources":%s,' "$([ "$_residual_resources" -eq 1 ] && printf true || printf false)"
    printf '"watchdog_disabled":%s,' "$([ "$_watchdog_disabled" -eq 1 ] && printf true || printf false)"
    printf '"purge_in_progress":%s' "$([ "$_purge_in_progress" -eq 1 ] && printf true || printf false)"
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
  printf 'loop query   : %s\n' "$_loop_check"
  printf 'rootfs mount : %s\n' "$([ "$_rootfs_mounted" -eq 1 ] && printf present || printf none)"
  printf 'rootfs ns    : %s\n' "$(case $_private_mount_check in 0) printf '%s' "$_private_mounts" ;; 1) printf none ;; 2) printf unknown ;; *) printf n/a ;; esac)"
  printf 'watchdog     : %s\n' "$_watchdog_label"
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

Read-only status. Validates holder PID/starttime/mount-namespace and a fresh
DSH health heartbeat if present. Never signals, unmounts, or detaches anything.
EOF
}
