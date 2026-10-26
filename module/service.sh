#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Non-blocking boot hook — P1 logs only. Do not add mount/loop/start here.

set -u

CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "service.sh: P1 skeleton; autostart disabled" >>"$LOG" 2>/dev/null || true
exit 0
