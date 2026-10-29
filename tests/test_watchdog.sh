#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
export DSH_WATCHDOG_LOCK_TIMEOUT=2
export DSH_WATCHDOG_INTERVAL=1
export DSH_WATCHDOG_STOP_TIMEOUT=5
export DSH_TEST_SKIP_NS_SCAN=1
. "$DSH_MODULE_DIR/lib/common.sh"
. "$DSH_MODULE_DIR/lib/config.sh"
. "$DSH_MODULE_DIR/lib/proc.sh"
dsh_config_init
WPID=
trap 'if [ -n "${WPID:-}" ]; then kill -TERM "$WPID" 2>/dev/null || true; wait "$WPID" 2>/dev/null || true; fi; [ "${DSH_TEST_KEEP:-0}" = 1 ] || rm -rf "$TMPBASE"' EXIT

start_daemon() {
  bash "$DSH_WATCHDOG" --daemon >"$CASE/daemon.log" 2>&1 &
  WPID=$!
  i=0
  while [ "$i" -lt 50 ] && { [ "$(cat "$DSH_CTL_DIR/watchdog.owner/pid" 2>/dev/null || true)" != "$WPID" ] ||
    [ ! -s "$DSH_CTL_DIR/watchdog.owner/starttime" ]; }; do
    sleep 0.1
    i=$((i + 1))
  done
  assert_eq "$(cat "$DSH_CTL_DIR/watchdog.owner/pid")" "$WPID"
}

start_daemon
i=0
while [ "$i" -lt 50 ] && ! grep -q -- '--claim-wait 0 .*/lifecycle.claim' "$CASE/helper.log"; do
  sleep 0.1
  i=$((i + 1))
done
grep -q -- '--claim-wait 0 .*/lifecycle.claim' "$CASE/helper.log" || fail "watchdog periodic check can block behind lifecycle stop"
bash "$DSH_WATCHDOG" --stop --disable
wait "$WPID"
[ -f "$DSH_CTL_DIR/watchdog.disabled" ] || fail "watchdog disable marker is missing"
[ ! -e "$DSH_CTL_DIR/watchdog.owner/pid" ] || fail "watchdog owner was not released"
bash "$DSH_WATCHDOG" --daemon
[ ! -e "$DSH_CTL_DIR/watchdog.owner/pid" ] || fail "disabled watchdog claimed ownership"
WPID=
: >"$CASE/helper.log"
bash "$DSH_WATCHDOG" --enable
[ ! -e "$DSH_CTL_DIR/watchdog.disabled" ] || fail "watchdog enable did not clear the marker"
grep -q -- '--claim-wait 120 .*/lifecycle.claim' "$CASE/helper.log" || fail "direct watchdog enable bypassed the lifecycle claim"

start_daemon
printf '1\n' >"$DSH_CTL_DIR/watchdog.owner/starttime"
: >"$CASE/helper.log"
set +e
bash "$DSH_WATCHDOG" --stop >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "watchdog stopped with a mismatched birth time"
grep -q -- '_lifecycle-claimed --stop' "$CASE/helper.log" || fail "mismatched birth time test did not reach watchdog stop"
kill -0 "$WPID" 2>/dev/null || fail "watchdog was killed despite identity mismatch"
printf '%s\n' "$(dsh_proc_starttime "$WPID")" >"$DSH_CTL_DIR/watchdog.owner/starttime"
bash "$DSH_WATCHDOG" --stop
wait "$WPID"
WPID=

mkdir -p "$DSH_CTL_DIR/watchdog.owner"
printf '%s\n' "$$" >"$DSH_CTL_DIR/watchdog.owner/pid"
printf '%s\n' "$(awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$$/stat")" >"$DSH_CTL_DIR/watchdog.owner/starttime"
set +e
bash "$DSH_WATCHDOG" --stop >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "watchdog stop accepted an unrelated process"

. "$DSH_MODULE_DIR/bin/dsh-watchdog" --help >/dev/null
start_daemon
old_owner=$WPID
set +e
dsh_watchdog_claim
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "duplicate watchdog claim succeeded"
assert_eq "$(cat "$DSH_CTL_DIR/watchdog.owner/pid")" "$old_owner"
bash "$DSH_WATCHDOG" --stop
wait "$old_owner"
WPID=

start_daemon
old_owner=$WPID
printf 'previous-boot\n' >"$DSH_WATCHDOG_BOOT_ID"
dsh_watchdog_claim
[ "$(cat "$DSH_CTL_DIR/watchdog.owner/pid")" != "$old_owner" ] || fail "stale boot id prevented a new claim"
kill -0 "$old_owner" 2>/dev/null || fail "stale owner process was killed"
printf '%s\n' "$old_owner" >"$DSH_CTL_DIR/watchdog.owner/pid"
dsh_proc_starttime "$old_owner" >"$DSH_CTL_DIR/watchdog.owner/starttime"
cat "$DSH_BOOT_ID_SOURCE" >"$DSH_WATCHDOG_BOOT_ID"
bash "$DSH_WATCHDOG" --stop
wait "$old_owner"
WPID=

start_daemon
printf 'previous-boot\n' >"$DSH_WATCHDOG_BOOT_ID"
set +e
bash "$DSH_WATCHDOG" --stop >/dev/null 2>&1
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "watchdog stop accepted a mismatched boot id"
kill -0 "$WPID" 2>/dev/null || fail "watchdog was killed despite boot id mismatch"
cat "$DSH_BOOT_ID_SOURCE" >"$DSH_WATCHDOG_BOOT_ID"
bash "$DSH_WATCHDOG" --stop
wait "$WPID"
WPID=

export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j)
    printf '%s: 0:0 (%s)\n' "$DSH_TEST_LOOP" "$DSH_IMAGE"
    [ "${DSH_TEST_EXTRA_LOOP:-0}" != 1 ] || printf '/dev/block/loop99: 0:0 (%s)\n' "$DSH_IMAGE"
    ;;
  "$DSH_TEST_LOOP") exit 0 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
exit "${DSH_TEST_NSENTER_RC:-0}"
EOF
chmod 0755 "$CASE/bin/losetup" "$CASE/bin/nsenter"
printf '%s\n' "$$" >"$DSH_PID_FILE"
dsh_proc_starttime "$$" >"$DSH_STARTTIME_FILE"
dsh_proc_ns "$$" >"$DSH_NS_FILE"
printf '%s\n' "$DSH_TEST_LOOP" >"$DSH_LOOP_FILE"
printf 'state=degraded\nnote=health-test\n' >"$DSH_STATUS_FILE"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_URL_FILE"
export DSH_TEST_NSENTER_RC=0
dsh_watchdog_once
assert_eq "$(dsh_state_get state)" "running"
export DSH_TEST_NSENTER_RC=1
set +e
dsh_watchdog_once
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "watchdog accepted a failed DSH health check"
assert_eq "$(dsh_state_get state)" "degraded"
export DSH_TEST_NSENTER_RC=0
dsh_watchdog_once
assert_eq "$(dsh_state_get state)" "running"
export DSH_TEST_EXTRA_LOOP=1
set +e
dsh_watchdog_once
rc=$?
set -e
unset DSH_TEST_EXTRA_LOOP
[ "$rc" -ne 0 ] || fail "watchdog accepted an unexpected loop attachment"
assert_eq "$(dsh_state_get state)" "degraded"
assert_eq "$(dsh_state_get note)" "unexpected-loop-attachment"

set +e
out=$(
  dsh_watchdog_claim() { return 0; }
  dsh_lifecycle_exec() {
    printf '%s\n' call >>"$DSH_TEST_ROOT/daemon-calls"
    return 0
  }
  sleep() { return 1; }
  dsh_watchdog_daemon 2>&1
)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "watchdog ignored a failed sleep"
assert_contains "$out" "sleep failed; stopping daemon"
assert_eq "$(wc -l <"$CASE/daemon-calls")" '1'

printf 'test_watchdog: ok\n'
