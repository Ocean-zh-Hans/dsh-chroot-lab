#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

set +e
out=$(DSH_TMP=relative "$DSH_CTL" config 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "relative DSH_TMP was accepted"
assert_contains "$out" "path must be absolute"

set +e
out=$(DSH_IMAGE="$CASE/outside.img" "$DSH_CTL" config 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "image outside DSH_IMAGE_DIR was accepted"
assert_contains "$out" "image must live under DSH_IMAGE_DIR"

ln -s "$CASE/mnt" "$CASE/rootfs-link"
set +e
out=$(DSH_ROOTFS_DIR="$CASE/rootfs-link" "$DSH_CTL" config 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "symlinked DSH_ROOTFS_DIR was accepted"
assert_contains "$out" "rootfs mountpoint must not be a symlink"

printf 'test_config: ok\n'
