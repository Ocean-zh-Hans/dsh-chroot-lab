#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
export DSH_MOUNTINFO="$CASE/proc/self/mountinfo"
export DSH_TEST_LOOP="$CASE/loop99"
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1
: >"$DSH_TEST_LOOP"

cat >"$CASE/bin/id" <<'EOF'
#!/bin/sh
echo 0
EOF
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
_state=$DSH_TEST_ROOT/losetup.state
case "${1:-}" in
  -j)
    [ ! -f "$_state" ] || printf '%s: 0:0 (%s)\n' "$DSH_TEST_LOOP" "$DSH_IMAGE"
    ;;
  -f)
    printf '%s\n' "$DSH_TEST_LOOP" >"$_state"
    printf '%s\n' "$DSH_TEST_LOOP"
    ;;
  -d) rm -f "$_state" ;;
  "$DSH_TEST_LOOP")
    if [ -f "$_state" ]; then exit 0; else exit 1; fi
    ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
exit 0
EOF
cat >"$CASE/bin/chroot" <<'EOF'
#!/bin/sh
IFS= read -r line || {
  printf 'chroot stdin was empty\n' >&2
  exit 91
}
printf 'chroot_input=%s\n' "$line"
EOF
chmod 0755 "$CASE/bin/id" "$CASE/bin/losetup" "$CASE/bin/chroot"

set +e
out=$(printf 'rescue-stdin-ok\n' | "$DSH_CTL" rescue --apply 2>&1)
rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  printf '%s\n' "$out"
  [ ! -r "$DSH_ROOT/logs/dsh-ctl.log" ] || cat "$DSH_ROOT/logs/dsh-ctl.log"
  fail "rescue returned $rc"
fi
assert_contains "$out" 'chroot_input=rescue-stdin-ok'
state=$(awk -F= '$1 == "state" { print $2 }' "$DSH_ROOT/state/ctl/status")
assert_eq "$state" stopped
printf 'test_rescue: ok\n'
