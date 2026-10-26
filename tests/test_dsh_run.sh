#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fake_dsh="$case_dir/fake-dsh"
cat >"$fake_dsh" <<'EOF'
#!/bin/sh
echo 'dsh web: http://127.0.0.1:1234/?token=test-token'
trap 'exit 0' TERM INT
while :; do sleep 0.2; done
EOF
chmod 0755 "$fake_dsh"

log="$case_dir/dsh.log"
url="$case_dir/url"
DSH_BIN="$fake_dsh" DSH_WEB_LOG="$log" DSH_WEB_URL_FILE="$url" "$MODULE/control/bin/dsh-run" &
runner=$!
i=0
while [ "$i" -lt 20 ]; do
  [ -s "$url" ] && break
  sleep 0.25
  i=$((i + 1))
done
[ -s "$url" ] || {
  cat "$log" 2>/dev/null || true
  kill "$runner" 2>/dev/null || true
  exit 1
}
assert_contains "$(cat "$url")" "http://127.0.0.1:1234/?token=test-token"
kill -TERM "$runner" 2>/dev/null || true
wait "$runner" 2>/dev/null || true
[ ! -e "$url" ] || fail "url file not removed on shutdown"
printf 'test_dsh_run: ok\n'
