#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
unset DSH_RISK_BYPASS

# R0 read-only commands are never gated.
out=$("$DSH_CTL" status 2>&1) || fail "read-only status was gated: $out"
assert_contains "$out" "state        :"

# R3 stop must fail closed with no reason/token.
set +e
out=$("$DSH_CTL" stop 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "R3 stop without confirmation returned $rc"
assert_contains "$out" "R3 requires DSH_RISK_REASON"

# A reason alone is not enough: a one-time token is mandatory.
set +e
out=$(DSH_RISK_REASON='operator requested stop' "$DSH_CTL" stop 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "R3 stop with reason but no token returned $rc"
assert_contains "$out" "requires a valid one-time token"

token=$("$DSH_CTL" risk issue --op stop --target holder \
  --reason 'operator requested stop' --impact 'stop the chroot holder' --level R3)
[ "${#token}" -ge 16 ] || fail "risk issue did not return a token"

set +e
out=$(DSH_RISK_REASON='operator requested stop' DSH_RISK_TOKEN="$token" "$DSH_CTL" stop 2>&1)
rc=$?
set -e
case $out in
*"requires a valid one-time token"*) fail "valid token was rejected" ;;
esac
[ "$rc" -eq 0 ] || [ "$rc" -eq 1 ] || fail "stop after valid token returned unexpected $rc"

# Tokens are one-op: a stop token must not authorize purge.
set +e
out=$(DSH_RISK_REASON='operator requested purge' DSH_RISK_TOKEN="$token" "$DSH_CTL" purge --yes 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "cross-op token reuse returned $rc"
assert_contains "$out" "requires a valid one-time token"

printf 'test_risk: ok\n'

# R4 is fail-closed without a real human TTY.
set +e
out=$(DSH_RISK_REASON='operator requested raw block write' DSH_RISK_HUMAN=YES \
  "$DSH_CTL" risk check --op block --sub full 2>&1 </dev/null)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "R4 without TTY returned $rc"
assert_contains "$out" "R4 requires a human TTY approval"
