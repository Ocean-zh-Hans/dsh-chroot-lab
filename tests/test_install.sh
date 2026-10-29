#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
cat >"$case_dir/bin/uname" <<'EOF'
#!/bin/sh
if [ "${1:-}" = -m ]; then printf 'aarch64\n'; exit 0; fi
exec /usr/bin/uname "$@"
EOF
cat >"$case_dir/bin/getprop" <<'EOF'
#!/bin/sh
if [ "${1:-}" = ro.build.version.sdk ]; then printf '35\n'; exit 0; fi
exit 0
EOF
cat >"$case_dir/bin/chcon" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/chcon.log"
EOF
chmod 0755 "$case_dir/bin/uname" "$case_dir/bin/getprop" "$case_dir/bin/chcon"
mkdir -p "$case_dir/module/tools/libexec" "$case_dir/module/tools/lib"
cp "$case_dir/module/tools/bin/cp" "$case_dir/module/tools/libexec/cp"
chmod 000 "$case_dir/module/tools/libexec/cp"
: >"$case_dir/module/tools/lib/libtest.so"
mkdir -p "$case_dir/root/state/ctl"
printf '1\n' >"$case_dir/root/state/ctl/watchdog.disabled"
printf '%s\n' "$$" >"$case_dir/root/state/guest/launcher.pid"
printf '1\n' >"$case_dir/root/state/guest/launcher.starttime"
readlink "/proc/$$/ns/mnt" >"$case_dir/root/state/guest/launcher.ns"
printf '%s\n' "$case_dir/loop99" >"$case_dir/root/state/guest/loopdev"
DSH_TEST_ROOT="$case_dir" MODPATH="$case_dir/module" DSH_ROOT="$case_dir/root" sh -c '
  . "$1/customize.sh"
  for TARGET in $REPLACE; do
    exit 1
  done
  for TARGET in $REMOVE; do
    exit 1
  done
  mktouch() {
    mkdir -p "${1%/*}" 2>/dev/null
    [ -z "$2" ] && touch "$1" || echo "$2" >"$1"
    chmod 644 "$1"
  }
  mktouch "$DSH_ROOT/install-marker"
' sh "$case_dir/module"
[ -x "$case_dir/root/control/bin/dsh-run" ] || fail 'control was not installed'
[ -f "$case_dir/root/control/README.md" ] || fail 'control configuration/recovery guide was not installed'
[ -x "$case_dir/module/tools/libexec/cp" ] || fail 'GNU cp is not executable'
[ "$(cat "$case_dir/root/state/ctl/install-version")" = 0.2.2 ] || fail 'install marker missing'
[ ! -e "$case_dir/root/state/ctl/watchdog.disabled" ] || fail 'explicit install did not enable watchdog'
for _path in tools/bin/dsh-mount-ro-recursive tools/libexec/cp tools/lib/libtest.so; do
  assert_contains "$(cat "$case_dir/chcon.log")" "u:object_r:shell_data_file:s0 $case_dir/module/$_path"
done
assert_eq "$(cat "$case_dir/root/state/guest/launcher.pid")" "$$"

awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$$/stat" >"$case_dir/root/state/guest/launcher.starttime"
set +e
out=$(DSH_TEST_ROOT="$case_dir" MODPATH="$case_dir/module" DSH_ROOT="$case_dir/root" sh -c '. "$1/customize.sh"' sh "$case_dir/module" 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'installer accepted a live holder'
assert_contains "$out" 'chroot holder is running'
rm -f "$case_dir/root/state/guest/launcher.pid" "$case_dir/root/state/guest/loopdev"

cat >"$case_dir/module/bin/dsh-watchdog" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/uninstall.log"
EOF
cat >"$case_dir/module/bin/dsh-ctl" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/uninstall.log"
EOF
chmod 0755 "$case_dir/module/bin/dsh-watchdog" "$case_dir/module/bin/dsh-ctl"
DSH_TEST_ROOT="$case_dir" DSH_ROOT="$case_dir/root" sh "$case_dir/module/uninstall.sh"
assert_eq "$(sed -n '1p' "$case_dir/uninstall.log")" "--stop --disable"
assert_eq "$(sed -n '2p' "$case_dir/uninstall.log")" "stop"
printf 'test_install: ok\n'
