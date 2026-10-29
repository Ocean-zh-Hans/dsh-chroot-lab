#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Boot-completed hook — log only; service.sh starts the lifecycle.

set -u

MODDIR=${0%/*}
CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
. "$MODDIR/lib/log.sh"
dsh_log_rotate_file "$LOG" 1048576 5 || exit 1
_state=$(getprop sys.boot_completed 2>/dev/null || true)
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "boot-completed.sh: boot_completed=${_state:-unknown}; autostart handled by service.sh" >>"$LOG" 2>/dev/null || true
exit 0
