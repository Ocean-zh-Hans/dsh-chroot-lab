#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Non-blocking late_start service hook. Boot logic runs in the background.

set -u

MODDIR=${0%/*}
CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
LOG=$CHROOT_HOME/logs/boot.log
WATCHDOG_LOG=$CHROOT_HOME/logs/watchdog.log
mkdir -p "$CHROOT_HOME/logs" 2>/dev/null || true
. "$MODDIR/lib/log.sh"
dsh_log_rotate_file "$LOG" 1048576 5 || printf '%s\n' 'service.sh: boot log rotation failed; continuing' >&2
dsh_log_rotate_file "$WATCHDOG_LOG" 1048576 3 || printf '%s\n' 'service.sh: watchdog log rotation failed; continuing' >&2

_auto=$(cat "$CHROOT_HOME/state/ctl/autostart" 2>/dev/null || true)
printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
  "service.sh: autostart=${_auto:-off}" >>"$LOG" 2>/dev/null || true

if [ "$_auto" = 1 ] && [ -x "$MODDIR/bin/dsh-ctl" ]; then
  (
    dsh_service_boot() {
      _attempt=$1
      _rc=0
      setsid "$MODDIR/bin/dsh-ctl" boot >>"$LOG" 2>&1 || _rc=$?
      printf '%s boot: attempt=%s rc=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" \
        "$_attempt" "$_rc" >>"$LOG" 2>/dev/null || true
      if [ "$_rc" -ne 0 ]; then
        "$MODDIR/bin/dsh-ctl" status 2>&1 |
          sed -E 's/([?&]token=)[^ &]+/\1REDACTED/g' >>"$LOG" 2>/dev/null || true
      fi
      return "$_rc"
    }
    if ! dsh_service_boot initial; then
      sleep 30 || {
        printf '%s\n' 'boot: retry delay failed; giving up' >>"$LOG" 2>/dev/null || true
        exit 1
      }
      dsh_service_boot retry || true
    fi
    if [ -x "$MODDIR/bin/dsh-log-limit" ]; then
      setsid "$MODDIR/bin/dsh-watchdog" --daemon 2>&1 |
        "$MODDIR/bin/dsh-log-limit" "$WATCHDOG_LOG"
    else
      printf '%s\n' 'watchdog: not started; dsh-log-limit is missing' >>"$LOG" 2>/dev/null || true
    fi
  ) &
fi

exit 0
