#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

printf 'abc' >"$DSH_IMAGE"
chmod 0600 "$DSH_IMAGE"

out=$("$DSH_CTL" snapshot create s1)
assert_contains "$out" "snapshot:"
[ -f "$DSH_IMAGE_DIR/snapshots/rootfs-s1.img" ] || fail "image snapshot missing"
[ -f "$DSH_ROOT/snapshots/state-s1.tar.gz" ] || fail "state snapshot missing"

out=$("$DSH_CTL" snapshot list)
assert_contains "$out" "rootfs-s1.img"
assert_contains "$out" "state-s1.tar.gz"

printf 'changed' >"$DSH_IMAGE"
out=$("$DSH_CTL" restore s1)
assert_contains "$out" "restored image"
assert_eq "$(cat "$DSH_IMAGE")" "abc"

out=$("$DSH_CTL" purge --yes --keep-image)
assert_contains "$out" "purged:"
[ ! -e "$DSH_ROOT" ] || fail "purge did not remove data root"
[ -f "$DSH_IMAGE" ] || fail "purge --keep-image removed image"

printf 'test_snapshot: ok\n'
