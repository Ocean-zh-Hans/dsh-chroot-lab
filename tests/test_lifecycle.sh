#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

# Use the real setsid so the holder gets its own process group; replace the
# other external commands with recording no-ops.
rm -f "$CASE/bin/setsid"
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"
export DSH_START_TIMEOUT=5
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1

cat >"$CASE/bin/id" <<'EOF'
#!/bin/sh
echo 0
EOF

cat >"$CASE/bin/busybox" <<'EOF'
#!/bin/sh
last=
for arg in "$@"; do last=$arg; done
exec "$last"
EOF

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) exit 0 ;;
  -f) printf '%s\n' "$DSH_TEST_LOOP"; exit 0 ;;
  -d) exit 0 ;;
  "$DSH_TEST_LOOP") exit 1 ;;
  --help) printf '%s\n' '--sector-size --direct-io'; exit 0 ;;
  *) exit 0 ;;
esac
EOF

cat >"$CASE/bin/mount" <<'EOF'
#!/bin/sh
printf 'mount %s\n' "$*" >>"$DSH_TEST_ROOT/mounts.log"
exit 0
EOF

cat >"$CASE/bin/umount" <<'EOF'
#!/bin/sh
printf 'umount %s\n' "$*" >>"$DSH_TEST_ROOT/umounts.log"
exit 0
EOF

cat >"$CASE/bin/mountpoint" <<'EOF'
#!/bin/sh
exit 0
EOF

cat >"$CASE/bin/chroot" <<'EOF'
#!/bin/sh
printf 'chroot %s\n' "$*" >>"$DSH_TEST_ROOT/chroot.log"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_ROOT/state/guest/url"
trap 'exit 0' TERM INT
while :; do sleep 0.2; done
EOF

cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--mount'; exit 0 ;;
esac
printf 'nsenter %s\n' "$*" >>"$DSH_TEST_ROOT/nsenter.log"
exit 0
EOF

chmod 0755 "$CASE/bin/id" "$CASE/bin/busybox" "$CASE/bin/losetup" "$CASE/bin/mount" \
  "$CASE/bin/umount" "$CASE/bin/mountpoint" "$CASE/bin/chroot" "$CASE/bin/nsenter"

set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  printf '%s\n' "$out"
  for f in "$DSH_ROOT/logs/nsinit.log" "$DSH_ROOT/logs/dsh-ctl.log"; do
    [ -r "$f" ] && {
      printf '\n-- %s --\n' "$f"
      cat "$f"
    }
  done
  exit 1
fi
assert_contains "$out" "started:"
assert_contains "$out" "url: http://127.0.0.1:1234/?token=test"

out=$("$DSH_CTL" status)
assert_contains "$out" "state        : running"
assert_contains "$out" "pid valid    : yes"
[ -s "$CASE/mounts.log" ] || fail "mount was not called"
grep -q 'ext4' "$CASE/mounts.log" || fail "rootfs ext4 mount missing"
grep -q -- '--rbind /data' "$CASE/helper.log" || fail "data rbind missing"

set +e
"$DSH_CTL" stop >"$TMPBASE/stop.out" 2>"$TMPBASE/stop.err"
_stop_rc=$?
set -e
if [ "$_stop_rc" -ne 0 ]; then
  printf 'stop rc=%s\n-- stdout --\n' "$_stop_rc"
  cat "$TMPBASE/stop.out" || true
  printf '\n-- stderr --\n'
  cat "$TMPBASE/stop.err" || true
  printf '\n-- state --\n'
  cat "$DSH_ROOT/state/ctl/status" 2>/dev/null || true
  printf '\n-- nsinit --\n'
  cat "$DSH_ROOT/logs/nsinit.log" 2>/dev/null || true
  exit 1
fi
out=$("$DSH_CTL" status)
assert_contains "$out" "state        : stopped"
[ ! -e "$DSH_ROOT/state/guest/launcher.pid" ] || fail "pid file not cleared"
[ ! -e "$DSH_ROOT/state/guest/launcher.ns" ] || fail "ns file not cleared"

printf 'test_lifecycle: ok\n'
