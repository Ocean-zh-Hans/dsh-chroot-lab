#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

out=$("$DSH_CTL" version)
assert_contains "$out" "dsh-ctl 0.2.0"

out=$("$DSH_CTL" help)
assert_contains "$out" "doctor [--for-apply]"
assert_contains "$out" "rescue --apply"

# Placeholder commands must report rc=3 and explicitly say no system changes.
set +e
out=$("$DSH_CTL" start 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "requires the explicit --apply flag"
assert_contains "$out" "DRY RUN ONLY"

set +e
out=$("$DSH_CTL" rescue 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "requires the explicit --apply flag"

set +e
out=$("$DSH_CTL" definitely-not-a-command 2>&1)
rc=$?
set -e
assert_eq "$rc" 2
assert_contains "$out" "unknown command"

# Config file must not override an explicit env value.
printf 'DSH_IMAGE_SIZE_GB=256\n' >"$DSH_ROOT/config.conf"
out=$(DSH_IMAGE_SIZE_GB=128 "$DSH_CTL" config)
assert_contains "$out" "DSH_IMAGE_SIZE_GB=128"
out=$("$DSH_CTL" config)
assert_contains "$out" "DSH_IMAGE_SIZE_GB=256"

# Missing modules must fail safely.
set +e
out=$(DSH_MODULE_DIR="$case_dir/missing" "$DSH_CTL" status 2>&1)
rc=$?
set -e
assert_eq "$rc" 2
assert_contains "$out" "missing library"

printf 'test_cli: ok\n'
