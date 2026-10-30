#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_PROC=/proc
export CASE
export DSH_TEST_ROOT="$CASE"
cat >"$case_dir/worker-lib.sh" <<'EOF'
. "$DSH_MODULE_DIR/lib/common.sh"
. "$DSH_MODULE_DIR/lib/config.sh"
. "$DSH_MODULE_DIR/lib/proc.sh"
. "$DSH_MODULE_DIR/lib/state.sh"
. "$DSH_MODULE_DIR/lib/lock.sh"
dsh_config_init
EOF
cat >"$case_dir/worker.sh" <<'EOF'
#!/usr/bin/env bash
set -u
. "$CASE/worker-lib.sh"
id=$1
for i in $(seq 1 20); do
  if dsh_lock_acquire; then
    if ! mkdir "$CASE/critical"; then
      printf 'overlap\n' >>"$CASE/overlap"
      exit 1
    fi
    sleep 0.05
    rmdir "$CASE/critical" || exit 1
    dsh_lock_release || exit 1
    printf 'acquired\n' >>"$CASE/$id.success"
  else
    sleep 0.01
  fi
done
EOF
chmod 0755 "$case_dir/worker.sh"
bash "$case_dir/worker.sh" a >"$case_dir/a.log" 2>&1 &
pid_a=$!
bash "$case_dir/worker.sh" b >"$case_dir/b.log" 2>&1 &
pid_b=$!
set +e
wait "$pid_a"
rc_a=$?
wait "$pid_b"
rc_b=$?
set -e
[ "$rc_a" -eq 0 ] && [ "$rc_b" -eq 0 ] || {
  cat "$case_dir/a.log" "$case_dir/b.log"
  fail "lock workers failed"
}
[ ! -e "$CASE/overlap" ] || fail "two processes entered the critical section together"
[ -s "$case_dir/a.success" ] || fail "worker a made no progress"
[ -s "$case_dir/b.success" ] || fail "worker b made no progress"
[ ! -e "$DSH_ROOT/state/ctl/lock" ] || fail "lock metadata remained after release"
. "$case_dir/worker-lib.sh"
mkdir "$DSH_CTL_DIR/lifecycle.claim"
(
  sleep 0.2
  rmdir "$DSH_CTL_DIR/lifecycle.claim" 2>/dev/null || true
) &
export DSH_LIFECYCLE_CLAIM_TIMEOUT=5
dsh_lifecycle_exec -c ':' || fail "lifecycle claim did not wait for a transient owner"
: >"$CASE/helper.log"
set +e
dsh_lifecycle_exec -c 'printf "called\n" >>"$CASE/child-calls"; exit 7'
rc=$?
set -e
assert_eq "$rc" '7'
assert_eq "$(wc -l <"$CASE/helper.log")" '1'
assert_eq "$(wc -l <"$CASE/child-calls")" '1'
unset DSH_LIFECYCLE_CLAIM_TIMEOUT
. "$case_dir/worker-lib.sh"
mkdir "$DSH_LOCK_DIR"
printf '99999999\n' >"$DSH_LOCK_DIR/pid"
dsh_lock_acquire || fail "stale lock was not reclaimed"
assert_eq "$(cat "$DSH_LOCK_DIR/pid")" "$$"
dsh_lock_release

# P2-6: a live PID with missing identity must not be treated as a live lock.
mkdir "$DSH_LOCK_DIR"
printf '%s\n' "$$" >"$DSH_LOCK_DIR/pid"
dsh_lock_acquire || fail "lock with missing identity was not reclaimed"
assert_eq "$(cat "$DSH_LOCK_DIR/pid")" "$$"
dsh_lock_release

REAL_MV=$(command -v mv)
export REAL_MV
export DSH_LOCK_DIR
cat >"$case_dir/bin/mv" <<'EOF'
#!/usr/bin/env bash
if [ "${DSH_TEST_RACE_STALE:-0}" = 1 ] && [ "${1:-}" = "$DSH_LOCK_DIR" ] && [[ ${2:-} == "$DSH_LOCK_DIR".stale.* ]] && [ ! -e "$CASE/race-a-ready" ]; then
  : >"$CASE/race-a-ready"
  for _ in $(seq 1 100); do
    [ -e "$CASE/race-b-acquired" ] && break
    sleep 0.05
  done
fi
exec "$REAL_MV" "$@"
EOF
chmod 0755 "$case_dir/bin/mv"
mkdir "$DSH_LOCK_DIR"
printf '99999999\n' >"$DSH_LOCK_DIR/pid"
export DSH_TEST_RACE_STALE=1
bash -c '. "$CASE/worker-lib.sh"; dsh_lock_acquire' >"$case_dir/race-a.log" 2>&1 &
pid_a=$!
for _ in $(seq 1 100); do
  [ -e "$case_dir/race-a-ready" ] && break
  sleep 0.05
done
[ -e "$case_dir/race-a-ready" ] || fail "first stale cleaner did not reach the race window"
bash -c '. "$CASE/worker-lib.sh"; i=0; while [ "$i" -lt 100 ]; do if dsh_lock_acquire; then break; fi; i=$((i + 1)); sleep 0.05; done; [ "$i" -lt 100 ] || exit 1; : >"$CASE/race-b-acquired"; for _ in $(seq 1 100); do [ -e "$CASE/race-a-done" ] && break; sleep 0.05; done; dsh_lock_release' >"$case_dir/race-b.log" 2>&1 &
pid_b=$!
set +e
wait "$pid_a"
rc_a=$?
: >"$case_dir/race-a-done"
wait "$pid_b"
rc_b=$?
set -e
unset DSH_TEST_RACE_STALE
[ "$rc_a" -eq 0 ] || fail "stale cleaner could not reclaim and acquire"
[ "$rc_b" -eq 0 ] || fail "replacement owner failed to acquire and release"
[ -e "$case_dir/race-b-acquired" ] || fail "replacement owner did not acquire after the cleaner finished"
[ ! -e "$DSH_LOCK_DIR" ] || fail "replacement lock was left after its owner released"
[ -z "$(find "$DSH_ROOT/state/ctl" -maxdepth 2 -type d -name 'lock.candidate.*' -print)" ] || fail "lock candidate directory was retained"
bash -c '. "$CASE/worker-lib.sh"; dsh_lock_acquire; sleep 2; dsh_lock_release' >"$case_dir/owner.log" 2>&1 &
pid_b=$!
sleep 0.2
set +e
bash -c '. "$CASE/worker-lib.sh"; dsh_lock_acquire' >"$case_dir/contender.log" 2>&1
rc_a=$?
set -e
wait "$pid_b"
[ "$rc_a" -ne 0 ] || fail "a live owner allowed a competing acquisition"
[ -z "$(find "$DSH_ROOT/state/ctl" -maxdepth 2 -type d -name 'lock.candidate.*' -print)" ] || fail "nested candidate directory was retained"
rm -f "$case_dir/bin/mv"
printf 'test_lock: ok\n'
