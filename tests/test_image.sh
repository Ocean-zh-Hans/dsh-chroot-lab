#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
rm -f "$DSH_IMAGE"

# Fake mke2fs/e2fsck already exist; real truncate creates a sparse file.
out=$("$DSH_CTL" image create --source "$DSH_ROOT" --size=1M --apply)
assert_contains "$out" "created image:"
[ -f "$DSH_IMAGE" ] || fail "image not created"
[ "$(stat -c %a "$DSH_IMAGE")" = "600" ] || fail "image mode is not 600"

set +e
out=$("$DSH_CTL" image create --source "$DSH_ROOT" --size=1M 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "use --apply"

printf 'test_image: ok\n'
