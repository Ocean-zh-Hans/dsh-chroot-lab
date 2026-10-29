# shellcheck shell=sh disable=SC3043
# Logging helpers. Timestamps are local time; logs are for humans, not audit.

dsh_timestamp() {
  date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date
}

dsh_log_rotate_file() {
  _log_file=$1
  _log_max=$2
  _log_keep=$3
  case $_log_max in
  '' | *[!0-9]*) return 2 ;;
  esac
  case $_log_keep in
  '' | *[!0-9]*) return 2 ;;
  esac
  [ "$_log_max" -gt 0 ] && [ "$_log_keep" -ge 1 ] || return 2
  for _log_existing in "$_log_file" "$_log_file".[0-9]*; do
    [ ! -f "$_log_existing" ] || chmod 0600 "$_log_existing" || return 1
  done
  if [ ! -f "$_log_file" ]; then
    : >>"$_log_file" || return 1
    chmod 0600 "$_log_file"
    return $?
  fi
  _log_size=$(wc -c <"$_log_file" 2>/dev/null | tr -d '[:space:]') || _log_size=0
  case $_log_size in
  '' | *[!0-9]*) _log_size=0 ;;
  esac
  [ "$_log_size" -gt "$_log_max" ] || {
    chmod 0600 "$_log_file"
    return $?
  }
  _log_i=$_log_keep
  while [ "$_log_i" -gt 1 ]; do
    _log_previous=$((_log_i - 1))
    if [ -f "$_log_file.$_log_previous" ]; then
      mv -f "$_log_file.$_log_previous" "$_log_file.$_log_i" || return 1
    fi
    _log_i=$((_log_i - 1))
  done
  mv -f "$_log_file" "$_log_file.1" || return 1
  : >"$_log_file" || return 1
  chmod 0600 "$_log_file"
}

dsh_log() {
  _dir=${DSH_LOG_DIR:-/data/adb/chroot/logs}
  mkdir -p "$_dir" 2>/dev/null || return 1
  printf '%s %s\n' "$(dsh_timestamp)" "$*" >>"$_dir/dsh-ctl.log" &&
    chmod 0600 "$_dir/dsh-ctl.log"
}

dsh_log_boot() {
  _dir=${DSH_LOG_DIR:-/data/adb/chroot/logs}
  mkdir -p "$_dir" 2>/dev/null || return 1
  printf '%s %s\n' "$(dsh_timestamp)" "$*" >>"$_dir/boot.log" &&
    chmod 0600 "$_dir/boot.log"
}
