#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_SKIP_NS_SCAN=1
# Slow Windows hosts still exercise explicit stale timestamps below.
export DSH_HEALTH_MAX_AGE=600

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
printf 'state=running\nnote=degraded-test\n' >"$DSH_ROOT/state/ctl/status"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_ROOT/state/runtime/url"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_ROOT/state/runtime/health"

out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : running"
assert_contains "$out" "pid valid    : yes"

json=$(DSH_PROC=/proc "$DSH_CTL" status --json)
python3 -c 'import json,sys; d=json.loads(sys.argv[1]); assert d["state"]=="running"; assert d["pid_valid"] is True; assert d["note"]=="degraded-test"' "$json"

printf 'ok:1\n' >"$DSH_ROOT/state/runtime/health"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : degraded"
printf 'state=degraded\nnote=health-test\n' >"$DSH_ROOT/state/ctl/status"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_ROOT/state/runtime/health"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : running"
rm -f "$DSH_ROOT/state/runtime/health"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : degraded"
printf 'state=running\n' >"$DSH_ROOT/state/ctl/status"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_ROOT/state/runtime/health"
printf '/dev/block/loop45\n' >"$DSH_ROOT/state/guest/loopdev"
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) printf '/dev/block/loop45: 0:0 (%s)\n/dev/block/loop46: 0:0 (%s)\n' "$DSH_IMAGE" "$DSH_IMAGE" ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
chmod 0755 "$CASE/bin/losetup"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : degraded"
rm -f "$DSH_ROOT/state/guest/loopdev"
cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
chmod 0755 "$CASE/bin/losetup"
printf '1\n' >"$DSH_ROOT/state/ctl/purge.in-progress"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "watchdog     : purge-in-progress"
json=$(DSH_PROC=/proc "$DSH_CTL" status --json)
python3 -c 'import json,sys; d=json.loads(sys.argv[1]); assert d["watchdog_disabled"] is True; assert d["purge_in_progress"] is True' "$json"
rm -f "$DSH_ROOT/state/ctl/purge.in-progress"
mv "$DSH_IMAGE" "$DSH_IMAGE.off"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : degraded"
assert_contains "$out" "note         : image-missing"
json=$(DSH_PROC=/proc "$DSH_CTL" status --json)
python3 -c 'import json,sys; d=json.loads(sys.argv[1]); assert d["state"]=="degraded"; assert d["note"]=="image-missing"; assert d["image_present"] is False' "$json"
mv "$DSH_IMAGE.off" "$DSH_IMAGE"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : running"

# Change only the recorded starttime: PID reuse/stale must be detected.
printf '1\n' >"$DSH_ROOT/state/guest/launcher.starttime"
out=$(DSH_PROC=/proc "$DSH_CTL" status)
assert_contains "$out" "state        : stale"
assert_contains "$out" "pid valid    : no"

printf 'test_status: ok\n'
