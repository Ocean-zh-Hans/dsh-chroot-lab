# shellcheck shell=sh disable=SC3043
# Logging helpers. Timestamps are local time; logs are for humans, not audit.

dsh_timestamp() {
  date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date
}

dsh_log() {
  _dir=${DSH_LOG_DIR:-/data/adb/chroot/logs}
  mkdir -p "$_dir" 2>/dev/null || return 1
  printf '%s %s\n' "$(dsh_timestamp)" "$*" >>"$_dir/dsh-ctl.log"
}

dsh_log_boot() {
  _dir=${DSH_LOG_DIR:-/data/adb/chroot/logs}
  mkdir -p "$_dir" 2>/dev/null || return 1
  printf '%s %s\n' "$(dsh_timestamp)" "$*" >>"$_dir/boot.log"
}
