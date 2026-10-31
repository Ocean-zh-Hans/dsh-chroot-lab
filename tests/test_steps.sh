#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
unset DSH_STEPS

# 默认（脚本、CI、手工 dsh-ctl）不许多出任何一行：现有输出与解析保持不变。
set +e
out=$("$DSH_CTL" start --apply 2>&1)
set -e
case $out in
*'#step:'*) fail "start printed step lines without DSH_STEPS=1" ;;
esac

# 控制台用 DSH_STEPS=1：进度一行一步，且不改变退出码语义。
set +e
out=$(DSH_STEPS=1 "$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "start without an image should fail, got rc=0"
assert_contains "$out" "#step: 检查当前状态与残留记录"
assert_contains "$out" "#step: 运行体检（--for-apply）"

# 步骤行不能混进 --json 输出，否则控制台的 JSON 解析会坏。
set +e
out=$(DSH_STEPS=1 "$DSH_CTL" status --json 2>&1)
set -e
case $out in
*'#step:'*) fail "status --json leaked step lines" ;;
*'"state"'*) ;;
*) fail "status --json did not look like JSON: $out" ;;
esac

printf 'test_steps: ok\n'
