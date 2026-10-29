#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_TEST_ROOT="$CASE"
cat >"$CASE/bin/chmod" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/chmod.log"
exec /usr/bin/chmod "$@"
EOF
chmod 0755 "$CASE/bin/chmod"
LOG=$CASE/watchdog.log
: >"$LOG"
chmod 0666 "$LOG"
i=0
while [ "$i" -lt 180 ]; do
  printf '%s\n' 'repeated watchdog failure'
  i=$((i + 1))
done | sh "$DSH_MODULE_DIR/bin/dsh-log-limit" "$LOG" 1024 2
[ "$(wc -l <"$LOG")" -lt 20 ] || fail 'duplicate log lines were not suppressed'
grep -q 'log-limit: suppressed' "$LOG" || fail 'duplicate suppression was not reported'
assert_eq "$(stat -c %a "$LOG")" '600'
grep -F "0600 $LOG" "$CASE/chmod.log" >/dev/null || fail 'watchdog log mode was not restricted'

i=0
while [ "$i" -lt 200 ]; do
  printf 'unique-%03d-012345678901234567890\n' "$i"
  i=$((i + 1))
done | sh "$DSH_MODULE_DIR/bin/dsh-log-limit" "$LOG" 1024 2
[ -f "$LOG.1" ] || fail 'size rotation did not keep the previous generation'
for path in "$LOG" "$LOG.1"; do
  [ "$(wc -c <"$path")" -lt 1100 ] || fail "log generation exceeded its bound: $path"
  assert_eq "$(stat -c %a "$path")" '600'
done
printf 'test_log_limit: ok\n'
