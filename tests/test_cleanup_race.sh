#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
rm -f "$CASE/bin/setsid"
if ! command -v setsid >/dev/null 2>&1; then
  cat >"$CASE/bin/setsid" <<'EOF'
#!/bin/sh
exec "$@"
EOF
  chmod 0755 "$CASE/bin/setsid"
fi
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
export DSH_MOUNTINFO="$CASE/proc/self/mountinfo"
export DSH_TEST_SKIP_MOUNTINFO=1
export DSH_TEST_LOOP="$CASE/loop99"
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1
export DSH_START_TIMEOUT=120
: >"$DSH_TEST_LOOP"

cat >"$CASE/bin/id" <<'EOF'
#!/bin/sh
echo 0
EOF
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
_state=$DSH_TEST_ROOT/losetup.state
case "${1:-}" in
  -j) [ ! -f "$_state" ] || printf '%s: 0:0 (%s)\n' "$DSH_TEST_LOOP" "$DSH_IMAGE" ;;
  -f)
    printf '%s\n' "$DSH_TEST_LOOP" >"$_state"
    printf '%s\n' "$DSH_TEST_LOOP"
    ;;
  -d) rm -f "$_state" ;;
  "$DSH_TEST_LOOP") [ ! -f "$_state" ] || exit 0; exit 1 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
exit 0
EOF
cat >"$CASE/bin/chroot" <<'EOF'
#!/bin/sh
printf 's6-svscan: not found\n' >&2
exit 127
EOF
cat >"$CASE/bin/umount" <<'EOF'
#!/bin/sh
if [ ! -e "$DSH_TEST_ROOT/umount-info-ready" ]; then
  while IFS= read -r target; do
    encoded=$(printf '%s' "$target" | awk '{ gsub(/ /, "\\\\040"); print }')
    printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded" >>"$DSH_MOUNTINFO"
  done <"$DSH_MOUNT_STACK"
  : >"$DSH_TEST_ROOT/umount-info-ready"
fi
sleep 0.2
encoded=$(printf '%s' "$1" | awk '{ gsub(/ /, "\\\\040"); print }')
awk -v target="$encoded" '$5 != target' "$DSH_MOUNTINFO" >"$DSH_MOUNTINFO.tmp"
mv "$DSH_MOUNTINFO.tmp" "$DSH_MOUNTINFO"
exit 0
EOF
cat >"$CASE/bin/mountpoint" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 0755 "$CASE/bin/id" "$CASE/bin/losetup" "$CASE/bin/chroot" \
  "$CASE/bin/umount" "$CASE/bin/mountpoint"

set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || {
  printf '%s\n' "$out"
  fail "expected startup failure rc=4, got $rc"
}
assert_contains "$out" 'holder exited before URL'
state=$(awk -F= '$1 == "state" { print $2 }' "$DSH_ROOT/state/ctl/status")
assert_eq "$state" failed
assert_contains "$(cat "$DSH_ROOT/logs/dsh-ctl.log")" 'cleanup: complete'
if ls "$DSH_ROOT/state/guest/"*.reverse.* >/dev/null 2>&1; then
  fail 'reverse mount list survived cleanup'
fi
"$DSH_CTL" stop >/dev/null
state=$(awk -F= '$1 == "state" { print $2 }' "$DSH_ROOT/state/ctl/status")
assert_eq "$state" stopped
printf 'test_cleanup_race: ok\n'
