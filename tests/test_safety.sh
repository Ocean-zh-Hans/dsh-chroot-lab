#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

marker="$case_dir/mount-called"
cat >"$CASE/bin/mount" <<EOF
#!/bin/sh
printf 'called\n' >>"$marker"
exit 0
EOF
chmod 0755 "$CASE/bin/mount"

"$DSH_CTL" plan >/dev/null
"$DSH_CTL" status >/dev/null
"$DSH_CTL" doctor >/dev/null 2>&1 || true
[ ! -e "$marker" ] || fail "P1 read-only command called mount"

set +e
"$DSH_MODULE_DIR/bin/dsh-nsinit" >/dev/null 2>&1
rc=$?
set -e
assert_eq "$rc" 3
[ ! -e "$marker" ] || fail "P1 dsh-nsinit called mount"

hooks="$MODULE/control/hooks/hooks.json"
grep -q 'PreToolUse' "$hooks" || fail "PreToolUse hook missing"
grep -q 'hardguard' "$MODULE/control/hooks/pretool.sh" || fail "hardguard missing"

printf 'test_safety: ok\n'
