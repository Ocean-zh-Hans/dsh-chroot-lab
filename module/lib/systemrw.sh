# shellcheck shell=sh disable=SC3043
# Optional system-rw overlay. Always inside the holder's private mount ns.

dsh_system_rw_parts='system system_ext product vendor odm'

dsh_system_rw_valid_part() {
  case " $dsh_system_rw_parts " in
  *" $1 "*) return 0 ;;
  *) return 1 ;;
  esac
}

dsh_system_rw_status() {
  _part=${1:-system}
  dsh_system_rw_valid_part "$_part" || {
    dsh_err "unsupported partition: $_part"
    return 2
  }
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if ! dsh_is_uint "$_pid" || ! dsh_proc_alive "$_pid"; then
    printf 'system-rw: holder not running\n'
    return 1
  fi
  if dsh_guest_shell "$_pid" "awk -v target='/$_part' '\$2 == target && \$3 == \"overlay\" { found=1 } END { exit !found }' /proc/mounts"; then
    printf 'system-rw: /%s overlay mounted\n' "$_part"
    return 0
  fi
  printf 'system-rw: /%s not overlaid\n' "$_part"
  return 1
}

dsh_system_rw_on() {
  _part=${1:-system}
  _apply=${2:-0}
  dsh_system_rw_valid_part "$_part" || {
    dsh_err "unsupported partition: $_part"
    return 2
  }
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if ! dsh_is_uint "$_pid" || ! dsh_proc_alive "$_pid"; then
    dsh_err "system-rw requires a running holder"
    return 1
  fi
  if [ "$_apply" != 1 ]; then
    printf 'system-rw: would overlay /%s inside the holder mount namespace (use --apply)\n' "$_part"
    return 3
  fi
  _dir=/var/lib/dsh/overlays/$_part
  dsh_guest_shell "$_pid" "set -e
if awk -v target='/$_part' '\$2 == target && \$3 == \"overlay\" { found=1 } END { exit !found }' /proc/mounts; then exit 0; fi
mkdir -p '$_dir/upper' '$_dir/work'
mount -t overlay overlay -o lowerdir=/$_part,upperdir='$_dir/upper',workdir='$_dir/work' /$_part" || {
    dsh_err "system-rw overlay failed: /$_part"
    return 1
  }
  return 0
}

dsh_system_rw_off() {
  _part=${1:-system}
  _apply=${2:-0}
  dsh_system_rw_valid_part "$_part" || {
    dsh_err "unsupported partition: $_part"
    return 2
  }
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if ! dsh_is_uint "$_pid" || ! dsh_proc_alive "$_pid"; then
    dsh_err "system-rw requires a running holder"
    return 1
  fi
  if [ "$_apply" != 1 ]; then
    printf 'system-rw: would unmount /%s inside the holder mount namespace (use --apply)\n' "$_part"
    return 3
  fi
  if ! dsh_guest_shell "$_pid" "awk -v target='/$_part' '\$2 == target && \$3 == \"overlay\" { found=1 } END { exit !found }' /proc/mounts"; then
    printf 'system-rw: /%s not overlaid\n' "$_part"
    return 0
  fi
  dsh_guest_shell "$_pid" "umount '/$_part' && ! awk -v target='/$_part' '\$2 == target && \$3 == \"overlay\" { found=1 } END { exit !found }' /proc/mounts" || {
    dsh_err "system-rw overlay removal failed: $_part"
    return 1
  }
  printf 'system-rw: /%s overlay removed\n' "$_part"
  return 0
}

dsh_system_rw() {
  _op=${1:-status}
  shift || true
  _part=system
  _apply=0
  for _arg in "$@"; do
    case $_arg in
    --apply) _apply=1 ;;
    -*)
      dsh_err "unknown system-rw option: $_arg"
      return 2
      ;;
    *) _part=$_arg ;;
    esac
  done
  case $_op in
  status) dsh_system_rw_status "$_part" ;;
  on) dsh_system_rw_on "$_part" "$_apply" && dsh_system_rw_status "$_part" ;;
  off) dsh_system_rw_off "$_part" "$_apply" ;;
  -h | --help)
    printf 'Usage: dsh-ctl system-rw status|on|off [partition] [--apply]\n'
    ;;
  *)
    dsh_err "unknown system-rw operation: $_op"
    return 2
    ;;
  esac
}
