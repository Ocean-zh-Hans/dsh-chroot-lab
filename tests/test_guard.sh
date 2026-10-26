#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

export DSH_HOOK_DIR="$MODULE/control/hooks"
export DSH_HOOK_STATE="$DSH_ROOT/state/guest/guard"
export DSH_AUDIT_DIR="$CASE/audit"
export DSH_HOOK_TMP="$CASE/audit"

out=$("$DSH_CTL" guard status)
assert_contains "$out" "guard: off"
"$DSH_CTL" guard on >/dev/null
assert_eq "$(cat "$DSH_HOOK_STATE")" "1"

out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"setenforce 0"}}' | "$MODULE/control/hooks/pretool.sh")
assert_contains "$out" '"permissionDecision":"deny"'

out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"rm -rf /data"}}' | "$MODULE/control/hooks/pretool.sh")
assert_contains "$out" '"permissionDecision":"ask"'

out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"ls /sdcard"}}' | "$MODULE/control/hooks/pretool.sh")
[ -z "$out" ] || fail "safe command should not be denied"

out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"ls"}}' | "$MODULE/control/hooks/posttool.sh")
[ -s "$DSH_AUDIT_DIR/posttool.log" ] || fail "posttool audit missing"

"$DSH_CTL" guard off >/dev/null
out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"setenforce 0"}}' | "$MODULE/control/hooks/pretool.sh")
assert_contains "$out" '"permissionDecision":"deny"'

printf 'test_guard: ok\n'
