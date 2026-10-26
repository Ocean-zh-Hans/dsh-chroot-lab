#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Run all local tests. No system modification, no mount/loop/unshare.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

tests=(
  "$ROOT/tests/test_cli.sh"
  "$ROOT/tests/test_plan.sh"
  "$ROOT/tests/test_doctor.sh"
  "$ROOT/tests/test_status.sh"
  "$ROOT/tests/test_safety.sh"
)

for t in "${tests[@]}"; do
  printf '\n== %s ==\n' "${t##*/}"
  bash "$t"
done

printf '\nall tests passed\n'
