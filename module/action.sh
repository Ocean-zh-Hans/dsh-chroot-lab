#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# Module action button: read-only status in P1.

set -u

MODDIR=${0%/*}
if [ -x "$MODDIR/bin/dsh-ctl" ]; then
  exec "$MODDIR/bin/dsh-ctl" status
fi
printf 'dsh-ctl not found under %s\n' "$MODDIR" >&2
exit 2
