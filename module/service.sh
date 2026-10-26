#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Non-blocking late_start service hook. Boot logic runs in the background.

set -u

MODDIR=${0%/*}
CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true

_auto=$(cat "$CHROOT_HOME/state/ctl/autostart" 2>/dev/null || true)
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "service.sh: autostart=${_auto:-off}" >>"$LOG" 2>/dev/null || true

# Optional frontend: after boot, package manager is alive. Do it in the
# background and never block boot.
if [ -f "$MODDIR/console.apk" ]; then
  if ! pm list packages dev.dsh.console 2>/dev/null | grep -q '^package:dev.dsh.console$'; then
    pm install -r "$MODDIR/console.apk" >>"$LOG" 2>&1 &
  fi
fi

if [ "$_auto" = 1 ] && [ -x "$MODDIR/bin/dsh-ctl" ]; then
  setsid "$MODDIR/bin/dsh-ctl" boot >>"$LOG" 2>&1 &
  _wpid=$(cat "$CHROOT_HOME/state/ctl/watchdog.pid" 2>/dev/null || true)
  if ! { [ -n "$_wpid" ] && kill -0 "$_wpid" 2>/dev/null; }; then
    setsid "$MODDIR/bin/dsh-watchdog" --daemon >>"$LOG" 2>&1 &
    printf '%s\n' "$!" >"$CHROOT_HOME/state/ctl/watchdog.pid" 2>/dev/null || true
  fi
fi

exit 0
