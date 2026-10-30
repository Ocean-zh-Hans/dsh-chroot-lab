#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1
export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"
ln -s "$DSH_TEST_LOOP" "$CASE/loop-alias"
: >"$CASE/other-loop"

cat >"$CASE/bin/readlink" <<'EOF'
#!/bin/sh
if [ "${1:-}" = -f ] && [ "${2:-}" = "$DSH_TEST_ROOT/loop-alias" ]; then
  printf '%s\n' "$DSH_TEST_LOOP"
  exit 0
fi
exec /usr/bin/readlink "$@"
EOF

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
_state=$DSH_TEST_ROOT/losetup.state
case "${1:-}" in
  -j)
    [ "${DSH_TEST_LOSETUP_J_FAIL:-0}" != 1 ] || exit 1
    if [ -f "$_state" ]; then
      _attached=$(cat "$_state")
      printf '%s: 0:0 (%s)\n' "$_attached" "$DSH_IMAGE"
    fi
    ;;
  -f)
    if [ "${DSH_TEST_FIND_BUSY:-0}" = 1 ]; then
      printf 'free device is busy\n' >&2
      exit 1
    fi
    if [ "${DSH_TEST_ATTACH_FAIL:-0}" = 1 ]; then
      printf 'kernel loop attach denied\n' >&2
      exit 1
    fi
    : >"$DSH_TEST_ROOT/attach-called"
    printf '%s\n' "$DSH_TEST_LOOP" >"$_state"
    printf '%s\n' "$DSH_TEST_LOOP"
    ;;
  "$DSH_TEST_ROOT/free-loop")
    if [ "$#" -eq 1 ]; then
      printf '%s\n' "$1: No such device or address" >&2
      exit 1
    fi
    : >"$DSH_TEST_ROOT/explicit-loop"
    ;;
  -d)
    printf '%s\n' "$2" >>"$DSH_TEST_ROOT/detached"
    rm -f "$_state"
    ;;
  --help)
    if [ "${DSH_TEST_TOYBOX:-0}" = 1 ]; then
      printf 'toybox losetup\n'
    else
      printf '%s\n' '--sector-size --direct-io'
    fi
    ;;
  "$DSH_TEST_LOOP" | "$DSH_TEST_ROOT/loop-alias" | "$DSH_TEST_ROOT/other-loop")
    [ -f "$_state" ]
    ;;
  *) exit 0 ;;
esac
EOF
cat >"$CASE/bin/e2fsck" <<'EOF'
#!/bin/sh
: >"$DSH_TEST_ROOT/e2fsck-called"
exit 0
EOF
chmod 0755 "$CASE/bin/readlink" "$CASE/bin/losetup" "$CASE/bin/e2fsck"
. "$DSH_MODULE_DIR/lib/common.sh"
. "$DSH_MODULE_DIR/lib/loop.sh"

mkdir -p "$DSH_SYS/class/block/loop99"
dsh_loop_same_device /dev/block/loop99 /dev/loop99 || fail "missing loop alias was not recognized"
printf '%s\n' "$CASE/loop-alias" >"$CASE/losetup.state"
dsh_loop_same_device "$DSH_TEST_LOOP" "$CASE/loop-alias" || fail "loop alias was not recognized"
dsh_loop_detach "$DSH_TEST_LOOP" "$DSH_IMAGE"
[ ! -e "$CASE/losetup.state" ] || fail "loop alias was not detached"
assert_eq "$(cat "$CASE/detached")" "$DSH_TEST_LOOP"

printf '%s\n' "$CASE/loop-alias" >"$CASE/losetup.state"
set +e
dsh_loop_detach "$CASE/other-loop" "$DSH_IMAGE" >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "a different device was detached"
assert_eq "$(cat "$CASE/losetup.state")" "$CASE/loop-alias"

export DSH_TEST_LOSETUP_J_FAIL=1
set +e
dsh_loop_assert_image_free "$DSH_IMAGE" >/dev/null 2>&1
rc=$?
set -e
unset DSH_TEST_LOSETUP_J_FAIL
[ "$rc" -ne 0 ] || fail "failed losetup query was treated as a free image"

set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "start accepted a pre-attached image"
assert_contains "$out" "image is already attached"
[ ! -e "$CASE/e2fsck-called" ] || fail "fsck ran against an attached image"
[ ! -e "$CASE/attach-called" ] || fail "a second loop attach was attempted"
[ -f "$CASE/losetup.state" ] || fail "a pre-existing loop was detached by the failed start"

rm -f "$CASE/losetup.state"
export DSH_TEST_ATTACH_FAIL=1 DSH_TEST_ALLOW_NON_DEV_LOOP=0
set +e
out=$(dsh_loop_attach "$DSH_IMAGE" 2>&1)
rc=$?
set -e
unset DSH_TEST_ATTACH_FAIL DSH_TEST_ALLOW_NON_DEV_LOOP
[ "$rc" -ne 0 ] || fail "failed loop attach returned success"
assert_contains "$out" "kernel loop attach denied"

: >"$CASE/free-loop"
export DSH_LOOP_CANDIDATES="$CASE/free-loop" DSH_TEST_FIND_BUSY=1 DSH_TEST_ALLOW_NON_DEV_LOOP=1 DSH_TEST_TOYBOX=1
out=$(dsh_loop_attach "$DSH_IMAGE")
unset DSH_LOOP_CANDIDATES DSH_TEST_FIND_BUSY DSH_TEST_ALLOW_NON_DEV_LOOP DSH_TEST_TOYBOX
assert_eq "$out" "$CASE/free-loop"
[ -f "$CASE/explicit-loop" ] || fail "verified free candidate was not attached explicitly"

# P2-3: if the loop record cannot be written, the just-attached loop must be
# detached immediately so stop/recover is not left without a loop identity.
rm -f "$CASE/losetup.state" "$CASE/detached"
rm -rf "$CASE/no-such-dir"
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
set +e
out=$(dsh_loop_attach_record "$DSH_IMAGE" "$CASE/no-such-dir/record" 2>&1)
rc=$?
set -e
unset DSH_TEST_ALLOW_NON_DEV_LOOP
[ "$rc" -ne 0 ] || fail "loop record write failure returned success"
assert_contains "$out" "cannot record loop"
[ ! -e "$CASE/losetup.state" ] || fail "loop was not detached after record write failure"
assert_contains "$(cat "$CASE/detached")" "$DSH_TEST_LOOP"

printf 'test_loop: ok\n'
