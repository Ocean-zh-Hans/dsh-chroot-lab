#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# KernelSU/Magisk customize.sh — install skeleton only.
# Does not extract payload, start chroot, or touch system mounts.

set -u

MODPATH=${MODPATH:-$(dirname -- "$0")}
CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
MODULE_VERSION=${MODULE_VERSION:-0.2.0}

ui_print() { printf '%s\n' "$*"; }
abort() {
  ui_print "! $*"
  exit 1
}

[ "$(uname -m 2>/dev/null || true)" = aarch64 ] || abort "arm64 only (got $(uname -m 2>/dev/null || true))"

_api=$(getprop ro.build.version.sdk 2>/dev/null || true)
case ${_api:-} in
'' | *[!0-9]*) _api=0 ;;
esac
[ "$_api" -ge 29 ] || abort "Android API 29+ required (got ${_api:-unknown})"

if [ -r "$CHROOT_HOME/state/guest/launcher.pid" ]; then
  _pid=$(cat "$CHROOT_HOME/state/guest/launcher.pid" 2>/dev/null || true)
  case $_pid in
  '' | *[!0-9]*) ;;
  *)
    if [ -d "/proc/$_pid" ]; then
      abort "chroot holder is running (pid $_pid); run dsh-ctl stop before updating"
    fi
    ;;
  esac
fi

mkdir -p "$CHROOT_HOME/control" "$CHROOT_HOME/state/ctl" "$CHROOT_HOME/state/guest" \
  "$CHROOT_HOME/state/dsh-home" "$CHROOT_HOME/logs" "$CHROOT_HOME/dsh" \
  "$CHROOT_HOME/snapshots" "$CHROOT_HOME/payload" || abort "cannot create $CHROOT_HOME"

cp -af "$MODPATH/control/." "$CHROOT_HOME/control/" || abort "cannot copy control files"

for _f in "$CHROOT_HOME"/control/s6/*/run "$CHROOT_HOME"/control/s6/*/finish; do
  [ -e "$_f" ] || continue
  chmod 0755 "$_f" 2>/dev/null || true
done
for _f in "$CHROOT_HOME"/control/bin/*; do
  [ -e "$_f" ] || continue
  chmod 0755 "$_f" 2>/dev/null || true
done
for _f in "$CHROOT_HOME"/control/hooks/*.sh; do
  [ -e "$_f" ] || continue
  chmod 0755 "$_f" 2>/dev/null || true
done

chmod 0700 "$CHROOT_HOME" "$CHROOT_HOME/control" "$CHROOT_HOME/state" 2>/dev/null || abort "cannot set permissions"

printf '%s\n' "$MODULE_VERSION" >"$CHROOT_HOME/state/ctl/install-version" || abort "cannot write install marker"

if [ -f "$MODPATH/console.apk" ]; then
  if pm install -r "$MODPATH/console.apk" >/dev/null 2>&1; then
    ui_print "- Chroot Console frontend installed"
  else
    ui_print "! Chroot Console auto-install failed; install $MODPATH/console.apk manually"
  fi
fi

ui_print "- DSH Ubuntu Chroot installed"
ui_print "- install only; apply/start is a separate explicit step"
ui_print "- Next: dsh-ctl doctor; dsh-ctl plan"
