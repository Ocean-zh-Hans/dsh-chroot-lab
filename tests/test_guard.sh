#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

export DSH_HOOK_STATE="$DSH_ROOT/state/runtime/guard"
export DSH_AUDIT_DIR="$CASE/audit"

out=$("$DSH_CTL" guard status)
assert_contains "$out" "guard: off"
"$DSH_CTL" guard on >/dev/null
assert_eq "$(cat "$DSH_HOOK_STATE")" "1"
"$DSH_CTL" guard off >/dev/null
assert_eq "$(cat "$DSH_HOOK_STATE")" "0"

grep -F 'approval-free:' "$MODULE/control/dsh/hooks.patch.yml" >/dev/null || fail 'approval-free id changed'
grep -F 'approval-guard:' "$MODULE/control/dsh/hooks.patch.yml" >/dev/null || fail 'approval-guard id changed'
grep -F 'name: 自动执行（不弹批准）' "$MODULE/control/dsh/hooks.patch.yml" >/dev/null || fail 'Chinese approval-free label missing'
grep -F 'name: 谨慎执行（逐项批准）' "$MODULE/control/dsh/hooks.patch.yml" >/dev/null || fail 'Chinese approval-guard label missing'

[ ! -e "$MODULE/control/hooks/pretool.sh" ] || fail "blocking pretool remains"
[ ! -e "$MODULE/control/hooks/deny.txt" ] || fail "deny list remains"
[ ! -e "$MODULE/control/hooks/ask.txt" ] || fail "ask list remains"
python3 - "$MODULE/control/hooks/hooks.json" <<'PY'
import json, sys
hooks = json.load(open(sys.argv[1]))['hooks']
assert 'PreToolUse' not in hooks
assert {'SessionStart', 'UserPromptSubmit', 'PostToolUse'} <= set(hooks)
PY

out=$(printf '%s' '{"hook_event_name":"SessionStart"}' | "$MODULE/control/hooks/riskcontext.sh")
assert_contains "$out" 'additionalContext'
assert_contains "$out" 'rootfs.img'
case $out in
*permissionDecision*) fail "risk context must not block" ;;
esac

out=$(printf '%s' '{"hook_event_name":"UserPromptSubmit","prompt":"dd of=/dev/block/loop45"}' | "$MODULE/control/hooks/riskcontext.sh")
assert_contains "$out" 'additionalContext'
case $out in
*permissionDecision*) fail "risk prompt must not block" ;;
esac
out=$(printf '%s' '{"hook_event_name":"UserPromptSubmit","prompt":"show mounts"}' | "$MODULE/control/hooks/riskcontext.sh")
[ -z "$out" ] || fail "ordinary prompt should not add risk context"

mkdir -p "$DSH_AUDIT_DIR"
printf 'legacy audit\n' >"$DSH_AUDIT_DIR/posttool.log"
printf 'expired audit\n' >"$DSH_AUDIT_DIR/posttool-2000-01-01.log"
touch -d '2000-01-01' "$DSH_AUDIT_DIR/posttool-2000-01-01.log"
out=$(printf '%s' '{"tool_name":"Bash","tool_input":{"command":"setenforce 0"}}' | "$MODULE/control/hooks/posttool.sh")
audit_log=$DSH_AUDIT_DIR/posttool-$(date '+%Y-%m-%d').log
[ -s "$audit_log" ] || fail "daily posttool audit missing"
if grep -q 'setenforce' "$audit_log"; then
  fail "audit leaked command text"
fi
[ ! -e "$DSH_AUDIT_DIR/posttool-2000-01-01.log" ] || fail "expired audit was not pruned"
[ -s "$DSH_AUDIT_DIR/posttool.log" ] || fail "legacy audit was silently deleted"

printf 'test_guard: ok\n'
