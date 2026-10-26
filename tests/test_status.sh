#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

# No status files yet.
out=$("$DSH_CTL" status)
assert_contains "$out" "state        : unknown"
assert_contains "$out" "pid          : none"

# A valid holder identity uses the test shell's own process.
pid=$$
mkdir -p "$DSH_ROOT/state/ctl" "$DSH_ROOT/state/guest"
starttime=$(awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$pid/stat")
ns=$(readlink "/proc/$pid/ns/mnt")
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
printf '%s\n' "$starttime" >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$ns" >"$DSH_ROOT/state/guest/launcher.ns"
printf 'state=running\n' >"$DSH_ROOT/state/ctl/status"

out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : running"
assert_contains "$out" "pid valid    : yes"

json=$(DSH_PROC=/proc "$DSH_CTL" status --json)
python3 -c 'import json,sys; d=json.loads(sys.argv[1]); assert d["state"]=="running"; assert d["pid_valid"] is True' "$json"

# Change only the recorded starttime: PID reuse/stale must be detected.
printf '1\n' >"$DSH_ROOT/state/guest/launcher.starttime"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : stale"
assert_contains "$out" "pid valid    : no"

printf 'test_status: ok\n'
