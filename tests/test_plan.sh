#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

out=$(DSH_IMAGE_SIZE_GB=128 "$DSH_CTL" plan)
assert_contains "$out" "DRY RUN ONLY"
assert_contains "$out" "1A full /data"
assert_contains "$out" "2B /sys rw"
assert_contains "$out" "3A full /dev"
assert_contains "$out" "6B critical paths recursive ro"
assert_contains "$out" "14B recursive ro /apex"
assert_contains "$out" "15A full /storage"
assert_contains "$out" "/sys/fs/selinux       private tmpfs mask"
assert_contains "$out" "Masks are integrity guards, not a security boundary"
assert_contains "$out" "Apply is explicit"

out=$(DSH_IMAGE_SIZE_GB=128 "$DSH_CTL" plan --check 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "Mode: apply-readiness"
case "$out" in
  *"Plan blocked"*) fail "plan --check should not be blocked in fixture" ;;
esac
assert_contains "$out" "== Mount plan =="

printf 'test_plan: ok\n'
