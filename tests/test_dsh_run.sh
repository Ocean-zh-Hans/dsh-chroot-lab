#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
RUNNER=
trap 'if [ -n "${RUNNER:-}" ]; then kill -TERM "$RUNNER" 2>/dev/null || true; wait "$RUNNER" 2>/dev/null || true; fi; [ "${DSH_TEST_KEEP:-0}" = 1 ] || rm -rf "$TMPBASE"' EXIT
fake_dsh="$case_dir/fake-dsh"
cat >"$fake_dsh" <<'EOF'
#!/bin/sh
printf '%s\n' "${DSH_PERMISSION_PRESET:-missing}" >"$DSH_TEST_ROOT/preset"
printf '%s\n' "$@" >"$DSH_TEST_ROOT/args"
printf '%s\n' "$$" >"$DSH_TEST_ROOT/child.pid"
if [ "${DSH_FAKE_NO_URL:-0}" != 1 ]; then
  printf '%s%s\n' "${DSH_FAKE_PREFIX:-dsh web: }" "${DSH_FAKE_URL:-http://127.0.0.1:1234/?token=test-token}"
fi
if [ "${DSH_FAKE_IGNORE_TERM:-0}" = 1 ]; then
  trap '' TERM INT
else
  trap 'printf "%s\n" "$$" >"$DSH_TEST_ROOT/child-term"; exit 0' TERM INT
fi
while :; do sleep 0.2; done
EOF
chmod 0755 "$fake_dsh"
cat >"$case_dir/health-ok.py" <<'EOF'
raise SystemExit(0)
EOF
export DSH_TEST_ROOT="$case_dir"
mkdir -p "$case_dir/profile/node_modules/@deepseek-ai/dsh-hooks-claude-code/lib" \
  "$case_dir/runtime/node_modules/@deepseek-ai/dsh-permission-presets/lib"
: >"$case_dir/profile/node_modules/@deepseek-ai/dsh-hooks-claude-code/lib/index.js"
: >"$case_dir/runtime/node_modules/@deepseek-ai/dsh-permission-presets/lib/index.js"

start_runner() {
  DSH_BIN="$fake_dsh" DSH_WEB_LOG="$case_dir/dsh.log" DSH_WEB_URL_FILE="$case_dir/url" \
    DSH_PROFILE_DIR="$case_dir/profile" DSH_RUNTIME_DIR="$case_dir/runtime" \
    DSH_CONTROL_DIR="$MODULE/control" DSH_HEALTHCHECK="$case_dir/health-ok.py" DSH_WEB_READY_TIMEOUT=2 \
    DSH_PYTHON="$(command -v python3)" DSH_HEALTH_INTERVAL=1 DSH_HEALTH_FAILURES=2 \
    DSH_WEB_STOP_TIMEOUT=1 DSH_GUARD_FILE="$case_dir/guard" \
    MSYS=winsymlinks:nativestrict "$MODULE/control/bin/dsh-run" &
  RUNNER=$!
}

stop_runner() {
  kill -TERM "$RUNNER" 2>/dev/null || true
  wait "$RUNNER" 2>/dev/null || true
  [ ! -e "$case_dir/url" ] || fail "url file not removed on shutdown"
  [ ! -e "$case_dir/health" ] || fail "health file not removed on shutdown"
}

start_runner
i=0
while [ "$i" -lt 20 ]; do
  [ -s "$case_dir/url" ] && break
  sleep 0.25
  i=$((i + 1))
done
[ -s "$case_dir/url" ] || {
  cat "$case_dir/dsh.log" 2>/dev/null || true
  stop_runner
  exit 1
}
assert_contains "$(cat "$case_dir/url")" "http://127.0.0.1:1234/?token=test-token"
assert_eq "$(cat "$case_dir/preset")" "approval-free"
stop_runner
assert_contains "$(cat "$case_dir/args")" 'runtime.patch.yml'
assert_contains "$(cat "$case_dir/args")" 'hooks.patch.yml'
assert_contains "$(cat "$case_dir/hooks.status")" '"mode":"full"'
[ -L "$case_dir/profile/node_modules/@deepseek-ai/dsh-permission-presets" ] || fail 'runtime permission preset was not linked into the profile'
rm -f "$case_dir/profile/node_modules/@deepseek-ai/dsh-permission-presets"
rm -rf "$case_dir/runtime/node_modules/@deepseek-ai/dsh-permission-presets"

printf '1\n' >"$case_dir/guard"
export DSH_FAKE_PREFIX='ready address='
start_runner
i=0
while [ "$i" -lt 20 ]; do
  [ -s "$case_dir/url" ] && break
  sleep 0.25
  i=$((i + 1))
done
[ -s "$case_dir/url" ] || fail "guard-mode DSH did not publish a URL"
assert_eq "$(cat "$case_dir/preset")" "approval-guard"
assert_contains "$(cat "$case_dir/url")" "http://127.0.0.1:1234/?token=test-token"
stop_runner
assert_contains "$(cat "$case_dir/args")" 'runtime.patch.yml'
assert_contains "$(cat "$case_dir/args")" 'hooks-audit-only.patch.yml'
assert_contains "$(cat "$case_dir/hooks.status")" '"mode":"audit-only"'
assert_contains "$(cat "$case_dir/dsh.log")" 'hooks mode=audit-only'
rm -f "$case_dir/profile/node_modules/@deepseek-ai/dsh-hooks-claude-code/lib/index.js"
unset DSH_FAKE_PREFIX
[ -s "$case_dir/dsh.log.1" ] || fail "previous startup log was not retained"

DSH_FAKE_URL='http://127.0.0.1.evil.example:1234/?token=bad'
export DSH_FAKE_URL
start_runner
i=0
while [ "$i" -lt 20 ] && kill -0 "$RUNNER" 2>/dev/null; do
  sleep 0.25
  i=$((i + 1))
done
if kill -0 "$RUNNER" 2>/dev/null; then
  stop_runner
  fail "non-loopback URL was accepted"
fi
set +e
wait "$RUNNER"
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "non-loopback runner exited successfully"
[ ! -s "$case_dir/url" ] || fail "non-loopback URL was published"
assert_contains "$(cat "$case_dir/hooks.status")" '"mode":"disabled"'
assert_contains "$(cat "$case_dir/args")" 'runtime.patch.yml'
if grep -q -- 'hooks.patch.yml\|hooks-audit-only.patch.yml' "$case_dir/args"; then
  fail 'disabled hooks still passed a hook patch to DSH'
fi
: >"$case_dir/profile/node_modules/@deepseek-ai/dsh-hooks-claude-code/lib/index.js"

export DSH_FAKE_NO_URL=1
rm -f "$case_dir/child-term"
start_runner
set +e
wait "$RUNNER"
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "startup timeout returned success"
[ -s "$case_dir/child-term" ] || fail "startup timeout left the web child running"
[ ! -e "$case_dir/url" ] || fail "startup timeout published a URL"
unset DSH_FAKE_NO_URL

export DSH_FAKE_NO_URL=1 DSH_FAKE_IGNORE_TERM=1
start_runner
set +e
wait "$RUNNER"
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "TERM-ignoring child returned success"
CHILD_PID=$(cat "$case_dir/child.pid")
if kill -0 "$CHILD_PID" 2>/dev/null; then
  kill -KILL "$CHILD_PID" 2>/dev/null || true
  fail "TERM-ignoring child was not killed"
fi
unset DSH_FAKE_NO_URL DSH_FAKE_IGNORE_TERM

cat >"$case_dir/health-ok.py" <<'EOF'
import os
import pathlib
path = pathlib.Path(os.environ['DSH_TEST_ROOT']) / 'health-count'
count = int(path.read_text()) + 1 if path.exists() else 1
path.write_text(str(count))
raise SystemExit(1 if count == 2 else 0)
EOF
export DSH_FAKE_URL='http://127.0.0.1:1234/?token=test-token'
start_runner
i=0
health_ready=0
while [ "$i" -lt 40 ]; do
  if [ -s "$case_dir/health-count" ] && [ "$(cat "$case_dir/health-count")" -ge 3 ]; then
    case $(cat "$case_dir/health" 2>/dev/null || true) in
    ok:*)
      health_ready=1
      break
      ;;
    esac
  fi
  sleep 0.25
  i=$((i + 1))
done
[ "$health_ready" -eq 1 ] || fail "healthy heartbeat did not recover after a transient failure"
kill -0 "$RUNNER" 2>/dev/null || fail "transient health failure killed the web child"
stop_runner

cat >"$case_dir/health-ok.py" <<'EOF'
import os
import pathlib
path = pathlib.Path(os.environ['DSH_TEST_ROOT']) / 'health-count'
count = int(path.read_text()) + 1 if path.exists() else 1
path.write_text(str(count))
raise SystemExit(0 if count == 1 else 1)
EOF
rm -f "$case_dir/child-term" "$case_dir/health-count"
start_runner
i=0
while [ "$i" -lt 40 ] && [ ! -s "$case_dir/url" ]; do
  sleep 0.25
  i=$((i + 1))
done
[ -s "$case_dir/url" ] || fail "healthy startup did not publish a URL"
set +e
wait "$RUNNER"
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "continuous health failure returned success"
[ -s "$case_dir/child-term" ] || fail "continuous health failure left the web child running"
[ ! -e "$case_dir/health" ] || fail "failed runner left a stale heartbeat"

cat >"$case_dir/health-ok.py" <<'EOF'
raise SystemExit(0)
EOF
_generation=0
while [ "$_generation" -lt 7 ]; do
  start_runner
  i=0
  while [ "$i" -lt 20 ] && [ ! -s "$case_dir/url" ]; do
    sleep 0.25
    i=$((i + 1))
  done
  [ -s "$case_dir/url" ] || fail "log-rotation startup did not publish a URL"
  stop_runner
  _generation=$((_generation + 1))
done
for _generation in 1 2 3 4 5; do
  [ -s "$case_dir/dsh.log.$_generation" ] || fail "missing startup log generation $_generation"
done
[ ! -e "$case_dir/dsh.log.6" ] || fail "startup logs exceeded five retained generations"

python3 "$ROOT/tests/test_healthcheck.py" "$MODULE/control/bin/dsh-healthcheck.py"
printf 'test_dsh_run: ok\n'
