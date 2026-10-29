#!/system/bin/sh
# shellcheck shell=sh disable=SC3043
# KernelSU/Magisk customize.sh — install skeleton only.
# Does not extract payload, start chroot, or touch system mounts.

REPLACE=
REMOVE=
export REPLACE REMOVE

set -u
MODPATH=${MODPATH:-$(dirname -- "$0")}
CHROOT_HOME=${DSH_ROOT:-/data/adb/chroot}
MODULE_VERSION=${MODULE_VERSION:-0.2.2}

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
      [ -s "$CHROOT_HOME/state/guest/launcher.starttime" ] && [ -s "$CHROOT_HOME/state/guest/launcher.ns" ] ||
        abort "holder identity is incomplete; run dsh-ctl recover --apply before updating"
      _recorded_st=$(cat "$CHROOT_HOME/state/guest/launcher.starttime" 2>/dev/null || true)
      _recorded_ns=$(cat "$CHROOT_HOME/state/guest/launcher.ns" 2>/dev/null || true)
      _recorded_boot=$(cat "$CHROOT_HOME/state/guest/launcher.bootid" 2>/dev/null || true)
      _current_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
      if [ -n "$_recorded_boot" ] && [ -z "$_current_boot" ]; then
        abort "cannot verify the current boot identity; refusing to update"
      fi
      if [ -z "$_recorded_boot" ] || [ "$_recorded_boot" = "$_current_boot" ]; then
        [ -n "$_recorded_st" ] && [ -n "$_recorded_ns" ] ||
          abort "holder identity is incomplete; run dsh-ctl recover --apply before updating"
        _actual_st=$(awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$_pid/stat" 2>/dev/null || true)
        _actual_ns=$(readlink "/proc/$_pid/ns/mnt" 2>/dev/null || true)
        [ -n "$_actual_st" ] && [ -n "$_actual_ns" ] ||
          abort "cannot verify holder identity; refusing to update"
        if [ "$_recorded_st" = "$_actual_st" ] && [ "$_recorded_ns" = "$_actual_ns" ]; then
          abort "chroot holder is running (pid $_pid); run dsh-ctl stop before updating"
        fi
      fi
      ui_print "! stale holder identity detected; no process will be signaled by the installer"
    fi
    ;;
  esac
fi
if [ -e "$CHROOT_HOME/state/guest/loopdev" ]; then
  ui_print "- runtime records retained; use the updated controller to recover them"
fi

mkdir -p "$CHROOT_HOME/control" "$CHROOT_HOME/state/ctl" "$CHROOT_HOME/state/guest" \
  "$CHROOT_HOME/state/dsh-home" "$CHROOT_HOME/logs" "$CHROOT_HOME/dsh" \
  "$CHROOT_HOME/snapshots" "$CHROOT_HOME/payload" "$CHROOT_HOME/rootfs-staging" || abort "cannot create $CHROOT_HOME"
rm -f "$CHROOT_HOME/state/ctl/watchdog.disabled" || abort "cannot enable watchdog for this installation"

cp -af "$MODPATH/control/." "$CHROOT_HOME/control/" || abort "cannot copy control files"
for _f in "$MODPATH"/bin/* "$MODPATH"/tools/bin/* "$MODPATH"/tools/libexec/*; do
  [ -f "$_f" ] || continue
  chmod 0755 "$_f" || abort "cannot make module tools executable"
done
for _f in "$MODPATH"/tools/bin/dsh-mount-ro-recursive "$MODPATH"/tools/libexec/* "$MODPATH"/tools/lib/*.so*; do
  [ -f "$_f" ] || continue
  chcon u:object_r:shell_data_file:s0 "$_f" || abort "cannot set executable context: $_f"
done

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

ui_print "- DSH Ubuntu Chroot installed"
ui_print "- install only; apply/start is a separate explicit step"
ui_print "- Next: dsh-ctl doctor; dsh-ctl plan"
set +u
