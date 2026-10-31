#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Run all local tests. No system modification, no mount/loop/unshare.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

tests=(
  "$ROOT/tests/test_cli.sh"
  "$ROOT/tests/test_risk.sh"
  "$ROOT/tests/test_install.sh"
  "$ROOT/tests/test_plan.sh"
  "$ROOT/tests/test_doctor.sh"
  "$ROOT/tests/test_status.sh"
  "$ROOT/tests/test_steps.sh"
  "$ROOT/tests/test_app.sh"
  "$ROOT/tests/test_proc.sh"
  "$ROOT/tests/test_safety.sh"
  "$ROOT/tests/test_loop.sh"
  "$ROOT/tests/test_loop_no_pipefail.sh"
  "$ROOT/tests/test_lock.sh"
  "$ROOT/tests/test_watchdog.sh"
  "$ROOT/tests/test_log_limit.sh"
  "$ROOT/tests/test_service.sh"
  "$ROOT/tests/test_lifecycle.sh"
  "$ROOT/tests/test_cleanup_race.sh"
  "$ROOT/tests/test_rescue.sh"
  "$ROOT/tests/test_mount_cleanup.sh"
  "$ROOT/tests/test_recovery_safety.sh"
  "$ROOT/tests/test_systemrw.sh"
  "$ROOT/tests/test_snapshot.sh"
  "$ROOT/tests/test_sparse.sh"
  "$ROOT/tests/test_guard.sh"
  "$ROOT/tests/test_ttyd.sh"
  "$ROOT/tests/test_image.sh"
  "$ROOT/tests/test_dsh_run.sh"
  "$ROOT/tests/test_android_env.sh"
  "$ROOT/tests/test_config.sh"
)

for t in "${tests[@]}"; do
  printf '\n== %s ==\n' "${t##*/}"
  bash "$t"
done

printf '\nall tests passed\n'
