#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1
export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) printf '%s: 0:0 (%s)\n' "$DSH_TEST_LOOP" "$DSH_IMAGE" ;;
  -d) printf '%s\n' "$2" >>"$DSH_TEST_ROOT/unowned-detached"; exit 0 ;;
  "$DSH_TEST_LOOP") exit 0 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
if [ "${1:-}" = --mount=/proc/self/ns/mnt ]; then exit 0; fi
sleep 10
EOF
chmod 0755 "$CASE/bin/losetup" "$CASE/bin/nsenter"

set +e
out=$("$DSH_CTL" stop 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "cleanup accepted an unowned loop"
assert_contains "$out" "refusing to detach unowned loops"
[ ! -e "$CASE/unowned-detached" ] || fail "an unowned loop was detached"
assert_contains "$("$DSH_CTL" status)" "state        : recovery-required"

export DSH_PROC=/proc
. "$DSH_MODULE_DIR/bin/dsh-watchdog" --help >/dev/null
printf '%s\n' "$$" >"$DSH_PID_FILE"
dsh_proc_starttime "$$" >"$DSH_STARTTIME_FILE"
dsh_proc_ns "$$" >"$DSH_NS_FILE"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_URL_FILE"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_HEALTH_FILE"
printf 'state=failed\nnote=test\n' >"$DSH_STATUS_FILE"
assert_contains "$("$DSH_CTL" status)" "state        : degraded"
printf 'state=stopped\n' >"$DSH_STATUS_FILE"
set +e
dsh_watchdog_once
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "inactive watchdog ignored a residual loop"

printf '%s\n' "$$" >"$DSH_PID_FILE"
printf '1\n' >"$DSH_STARTTIME_FILE"
printf '%s\n' "$DSH_TEST_LOOP" >"$DSH_LOOP_FILE"
set +e
out=$("$DSH_CTL" recover --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "stale recovery detached an image with an active loop"
assert_contains "$out" "image still has loop attachments"
[ ! -e "$CASE/unowned-detached" ] || fail "stale recovery detached a loop"
[ -e "$DSH_PID_FILE" ] || fail "unsafe stale recovery discarded identity records"

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) exit 0 ;;
  -d) printf '%s\n' "$2" >>"$DSH_TEST_ROOT/unowned-detached"; exit 0 ;;
  "$DSH_TEST_LOOP") exit 1 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
chmod 0755 "$CASE/bin/losetup"
out=$("$DSH_CTL" recover --apply 2>&1)
assert_contains "$out" "recover: ok"
assert_contains "$("$DSH_CTL" status)" "state        : stopped"
[ ! -e "$DSH_PID_FILE" ] || fail "stale pid was not cleared"
[ ! -e "$DSH_LOOP_FILE" ] || fail "stale loop record was not cleared"
[ ! -e "$CASE/unowned-detached" ] || fail "stale recovery detached an unowned loop"

printf '%s\n' "$$" >"$DSH_PID_FILE"
printf '1\n' >"$DSH_STARTTIME_FILE"
dsh_proc_any_rootfs() { return 2; }
set +e
out=$(dsh_recover_stale_locked 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "stale recovery ignored a rootfs scan failure"
[ -e "$DSH_PID_FILE" ] || fail "failed stale recovery discarded identity records"
rm -f "$DSH_PID_FILE" "$DSH_STARTTIME_FILE"
unset -f dsh_proc_any_rootfs

dsh_proc_starttime "$$" >"$DSH_STARTTIME_FILE"
dsh_proc_ns "$$" >"$DSH_NS_FILE"
printf 'previous-boot\n' >"$DSH_BOOT_ID_FILE"
if dsh_proc_validate_identity "$$" "$(cat "$DSH_STARTTIME_FILE")" "$(cat "$DSH_NS_FILE")"; then
  fail "identity validation ignored a previous boot id"
fi
rm -f "$DSH_BOOT_ID_FILE"
dsh_proc_validate_identity "$$" "$(cat "$DSH_STARTTIME_FILE")" "$(cat "$DSH_NS_FILE")" || fail "legacy identity validation failed"
started=$(date +%s)
set +e
DSH_GUEST_COMMAND_TIMEOUT=1 dsh_guest_run "$$" true
rc=$?
set -e
elapsed=$(($(date +%s) - started))
[ "$rc" -eq 124 ] || [ "$rc" -eq 137 ] || fail "guest command timeout returned $rc"
[ "$elapsed" -lt 15 ] || fail "guest command timeout took ${elapsed}s"

export DSH_TEST_SKIP_NS_SCAN=0
dsh_proc_ns "$$" >"$DSH_NS_FILE"
printf '%s\n' "$DSH_TEST_LOOP" >"$DSH_LOOP_FILE"
: >"$CASE/scan-count"
dsh_proc_scan_rootfs_ns() {
  count=$(cat "$DSH_TEST_ROOT/scan-count")
  count=$((count + 1))
  printf '%s\n' "$count" >"$DSH_TEST_ROOT/scan-count"
  [ "$count" -ne 1 ] || printf '%s\n' "$$"
}
dsh_recovery_run() { return 1; }
dsh_recovery_kill_ns() { return 0; }
dsh_loop_detach() {
  : >"$DSH_TEST_ROOT/detach-after-failed-recovery"
  return 0
}
dsh_loop_attached_images() { return 0; }
set +e
dsh_recover_dead_locked
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "failed recovery script was accepted when the namespace was empty"
[ ! -e "$CASE/detach-after-failed-recovery" ] || fail "loop detach continued after failed recovery"
dsh_recovery_run() { return 0; }
dsh_proc_scan_rootfs_ns() { return 2; }
set +e
dsh_recover_dead_locked
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "namespace scan failure allowed loop detach"
[ ! -e "$CASE/detach-after-failed-recovery" ] || fail "loop detach continued after namespace scan failure"
dsh_proc_scan_rootfs_ns() { return 0; }
dsh_proc_any_rootfs() { return 2; }
set +e
dsh_recover_dead_locked
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "unreadable namespace state allowed loop detach"
[ ! -e "$CASE/detach-after-failed-recovery" ] || fail "loop detach continued after namespace verification failed"

printf 'test_recovery_safety: ok\n'
