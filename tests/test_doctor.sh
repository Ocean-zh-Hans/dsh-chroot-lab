#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

out=$("$DSH_CTL" doctor 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "global SELinux enforce=1"
assert_contains "$out" "domain is u:r:ksu:s0"
assert_contains "$out" "rootfs image is private"
assert_contains "$out" "recursive-ro helper present"
assert_contains "$out" "Summary:"

out=$("$DSH_CTL" doctor --for-apply 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "Mode: apply-readiness"
assert_contains "$out" "Summary:"

# A rootfs mountpoint under /data is a self-reference hazard and must FAIL.
set +e
out=$(DSH_TEST_ALLOW_UNUSUAL_PATHS=0 DSH_ROOTFS_DIR=/data/dsh-rootfs "$DSH_CTL" doctor 2>&1)
rc=$?
set -e
assert_eq "$rc" 4
assert_contains "$out" "rootfs mountpoint must not be under"

printf 'test_doctor: ok\n'
