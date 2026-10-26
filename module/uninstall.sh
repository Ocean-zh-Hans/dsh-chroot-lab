#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Uninstall hook — stop holder, preserve data; purge is explicit.

set -u

CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
MODDIR=${0%/*}
if [ -x "$MODDIR/bin/dsh-ctl" ]; then
  "$MODDIR/bin/dsh-ctl" stop >>"$LOG" 2>&1 || true
fi
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "uninstall.sh: module removed; keeping $CHROOT_HOME and image data" >>"$LOG" 2>/dev/null || true
exit 0
