#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
mkdir -p "$DSH_ROOT/logs" "$DSH_ROOT/state/ctl"
printf '1\n' >"$DSH_ROOT/state/ctl/autostart"
truncate -s 1048577 "$DSH_ROOT/logs/boot.log"
chmod 0666 "$DSH_ROOT/logs/boot.log"
: >"$CASE/boot-count"

cat >"$CASE/bin/setsid" <<'EOF'
#!/bin/sh
exec "$@"
EOF
cat >"$CASE/bin/sleep" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/sleeps"
exit 0
EOF
cat >"$DSH_MODULE_DIR/bin/dsh-ctl" <<'EOF'
#!/bin/sh
case ${1:-} in
  boot)
    count=$(cat "$DSH_TEST_ROOT/boot-count")
    count=$((count + 1))
    printf '%s\n' "$count" >"$DSH_TEST_ROOT/boot-count"
    [ "$count" -ne 1 ] || exit 4
    exit 0
    ;;
  status)
    printf 'state        : recovery-required\n'
    printf 'url          : http://127.0.0.1:1234/?token=test\n'
    ;;
esac
exit 0
EOF
cat >"$DSH_MODULE_DIR/bin/dsh-watchdog" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/watchdog-calls"
exit 0
EOF
cat >"$DSH_MODULE_DIR/bin/dsh-log-limit" <<'EOF'
#!/bin/sh
cat >>"$DSH_ROOT/logs/watchdog.log"
EOF
cat >"$CASE/bin/chmod" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/chmod.log"
exec /usr/bin/chmod "$@"
EOF
chmod 0755 "$CASE/bin/chmod" "$CASE/bin/setsid" "$CASE/bin/sleep" "$DSH_MODULE_DIR/bin/dsh-ctl" \
  "$DSH_MODULE_DIR/bin/dsh-watchdog" "$DSH_MODULE_DIR/bin/dsh-log-limit"

DSH_ROOT="$DSH_ROOT" sh "$DSH_MODULE_DIR/service.sh"
i=0
while [ "$i" -lt 100 ] && ! grep -q 'boot: attempt=retry rc=0' "$DSH_ROOT/logs/boot.log"; do
  /usr/bin/sleep 0.1
  i=$((i + 1))
done
/usr/bin/sleep 0.2
assert_eq "$(cat "$CASE/boot-count")" '2'
assert_eq "$(cat "$CASE/sleeps")" '30'
assert_eq "$(cat "$CASE/watchdog-calls")" '--daemon'
grep -q 'boot: attempt=initial rc=4' "$DSH_ROOT/logs/boot.log" || fail 'initial boot failure was not logged'
grep -q 'boot: attempt=retry rc=0' "$DSH_ROOT/logs/boot.log" || fail 'boot retry was not logged'
grep -q 'token=REDACTED' "$DSH_ROOT/logs/boot.log" || fail 'failure status was not recorded'
if grep -q 'token=test' "$DSH_ROOT/logs/boot.log"; then
  fail 'boot log exposed a token'
fi
[ -f "$DSH_ROOT/logs/boot.log.1" ] || fail 'oversized boot log was not rotated'
assert_eq "$(stat -c %a "$DSH_ROOT/logs/boot.log")" '600'
assert_eq "$(stat -c %a "$DSH_ROOT/logs/watchdog.log")" '600'
grep -F "0600 $DSH_ROOT/logs/boot.log" "$CASE/chmod.log" >/dev/null || fail 'boot log mode was not restricted'
printf 'test_service: ok\n'
