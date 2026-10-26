#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Boot-completed hook — P1 read-only check/log. Do not start chroot here.

set -u

CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
_state=$(getprop sys.boot_completed 2>/dev/null || true)
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "boot-completed.sh: boot_completed=${_state:-unknown}; P1 autostart disabled" >>"$LOG" 2>/dev/null || true
exit 0
