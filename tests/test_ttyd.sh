#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

out=$("$DSH_CTL" ttyd status)
assert_contains "$out" "configured off"
export DSH_TEST_ROOT="$CASE"
pid=$$
mkdir -p "$DSH_PROC/$pid"
{
  printf '%s (test) S' "$pid"
  for _field in 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21; do printf ' 0'; done
  printf ' 12345\n'
} >"$DSH_PROC/$pid/stat"
printf '12345\n' >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
printf 'mnt:[4026531840]\n' >"$DSH_ROOT/state/guest/launcher.ns"
cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --mount=*) exit 1 ;;
esac
printf 'nsenter %s\n' "$*" >>"$DSH_TEST_ROOT/nsenter.log"
exit 0
EOF
chmod 0755 "$CASE/bin/nsenter"
out=$("$DSH_CTL" ttyd on)
assert_contains "$out" "enabled with authentication"
[ ! -e "$DSH_ROOT/control/s6/ttyd/down" ] || fail "ttyd was not enabled"
grep -Eq -- '-t [0-9]+ -m -- chroot .*/usr/bin/s6-svc -u /run/s6/ttyd' "$CASE/nsenter.log" || fail "ttyd was not explicitly started"
! grep -Eq -- '/usr/bin/s6-svc -r /run/s6/ttyd' "$CASE/nsenter.log" || fail "ttyd restart did not start a down service"
export DSH_PROC="$case_dir/proc"
pass=$DSH_ROOT/state/runtime/ttyd.pass
[ -f "$pass" ] || fail "credential file missing"
assert_eq "$(stat -c %a "$pass")" "600"
assert_eq "$(wc -c <"$pass" | tr -d ' ')" "65"
grep -q -- '-c "dsh:$_pass"' "$MODULE/control/s6/ttyd/run" || fail "ttyd authentication missing"
out=$("$DSH_CTL" ttyd off)
assert_contains "$out" "disabled"
[ -f "$DSH_ROOT/control/s6/ttyd/down" ] || fail "ttyd was not disabled"

rm -f "$pass"
mv "$DSH_PROC/sys/kernel/random/uuid" "$DSH_PROC/sys/kernel/random/uuid.off"
set +e
out=$("$DSH_CTL" ttyd on 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "ttyd started without credentials"
[ -f "$DSH_ROOT/control/s6/ttyd/down" ] || fail "failed ttyd enable removed the down guard"

printf 'test_ttyd: ok\n'
