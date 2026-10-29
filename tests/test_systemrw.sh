#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
pid=$$
awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$pid/stat" >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
readlink "/proc/$pid/ns/mnt" >"$DSH_ROOT/state/guest/launcher.ns"

cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--mount'; exit 0 ;;
esac
printf 'root=%s args=%s\n' "$DSH_TEST_ROOT" "$*" >>"$DSH_TEST_ROOT/nsenter.log"
_flat=$(printf '%s' "$*" | tr '\n' ' ')
case " $_flat " in
  *" mount -t overlay "*)
    : >"$DSH_TEST_ROOT/overlay"
    ;;
  *" umount "*)
    rm -f "$DSH_TEST_ROOT/overlay"
    ;;
  *" /proc/mounts "*)
    [ -e "$DSH_TEST_ROOT/overlay" ] || exit 1
    ;;
esac
if [ -e "$DSH_TEST_ROOT/overlay" ]; then printf 'overlay=yes\n' >>"$DSH_TEST_ROOT/nsenter.log"; else printf 'overlay=no\n' >>"$DSH_TEST_ROOT/nsenter.log"; fi
exit 0
EOF
chmod 0755 "$CASE/bin/nsenter"

set +e
out=$("$DSH_CTL" system-rw status system)
rc=$?
set -e
assert_eq "$rc" 1
assert_contains "$out" "not overlaid"
out=$("$DSH_CTL" system-rw on system --apply)
assert_contains "$out" "overlay mounted"
[ -e "$CASE/overlay" ] || fail "overlay command was not called"
out=$("$DSH_CTL" system-rw off system --apply)
assert_contains "$out" "overlay removed"
[ ! -e "$CASE/overlay" ] || fail "overlay was not removed"
out=$("$DSH_CTL" system-rw off system --apply)
assert_contains "$out" "not overlaid"

printf 'test_systemrw: ok\n'
