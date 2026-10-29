#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Uninstall hook — stop watchdog and holder, preserve data; purge is explicit.

set -u

CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
MODDIR=${0%/*}
WATCHDOG_WAS_DISABLED=0
[ ! -e "$CHROOT_HOME/state/ctl/watchdog.disabled" ] || WATCHDOG_WAS_DISABLED=1
if [ -x "$MODDIR/bin/dsh-watchdog" ]; then
  if ! "$MODDIR/bin/dsh-watchdog" --stop --disable >>"$LOG" 2>&1; then
    printf '%s\n' "uninstall.sh: watchdog stop failed; refusing to remove the running module" >>"$LOG"
    exit 1
  fi
fi
if [ -x "$MODDIR/bin/dsh-ctl" ]; then
  if ! "$MODDIR/bin/dsh-ctl" stop >>"$LOG" 2>&1; then
    if [ "$WATCHDOG_WAS_DISABLED" -eq 0 ] && [ -x "$MODDIR/bin/dsh-watchdog" ]; then
      "$MODDIR/bin/dsh-watchdog" --enable >>"$LOG" 2>&1 || true
    fi
    printf '%s\n' "uninstall.sh: stop failed; refusing to remove the running module" >>"$LOG"
    exit 1
  fi
fi
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "uninstall.sh: module removed; keeping $CHROOT_HOME and image data" >>"$LOG" 2>/dev/null || true
exit 0
