#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
# Exercise the real gates here; lib.sh sets the bypass globally for other tests.
unset DSH_RISK_BYPASS

# Software-layer probes need a running chroot: without one they fail closed.
set +e
out=$("$DSH_CTL" app version 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || fail "app version without a running chroot returned $rc"
assert_contains "$out" "需要 chroot 正在运行"

# Fabricate a running holder owned by this shell.
pid=$$
ns=$(readlink "/proc/$pid/ns/mnt")
starttime=$(awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$pid/stat")
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
printf '%s\n' "$starttime" >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$ns" >"$DSH_ROOT/state/guest/launcher.ns"
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
printf '%s\n' "$starttime" >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$ns" >"$DSH_ROOT/state/guest/launcher.ns"
printf 'state=running\n' >"$DSH_ROOT/state/ctl/status"
# The holder identity is a real process, so probe the real /proc here.
export DSH_PROC=/proc
printf '{"mode":"full","reason":"requested","audit":true,"permissionPresets":true}\n' \
  >"$DSH_ROOT/state/runtime/hooks.status"

# Version switching works on a stopped chroot only.
mkdir -p "$DSH_ROOT/dsh/versions/1.4.1/bin" "$DSH_ROOT/dsh/versions/1.4.2/bin"
printf '#!/bin/sh\n' >"$DSH_ROOT/dsh/versions/1.4.1/bin/dsh"
printf '#!/bin/sh\n' >"$DSH_ROOT/dsh/versions/1.4.2/bin/dsh"
chmod 0755 "$DSH_ROOT/dsh/versions/1.4.1/bin/dsh" "$DSH_ROOT/dsh/versions/1.4.2/bin/dsh"
ln -s versions/1.4.2 "$DSH_ROOT/dsh/current"

probe="$case_dir/probe.txt"
cat >"$probe" <<'PROBE'
dsh_version=1.4.2
dsh_bin=/opt/dsh/versions/1.4.2/bin/dsh
dsh_current=/opt/dsh/versions/1.4.2
dsh_previous=/opt/dsh/versions/1.4.1
node_version=v24.4.1
pnpm_version=9.12.0
hooks_status={"mode":"full","reason":"requested","audit":true,"permissionPresets":true}
PROBE
export DSH_APP_PROBE_FILE="$probe"

out=$("$DSH_CTL" app version)
assert_contains "$out" "dsh       : 1.4.2"
assert_contains "$out" "hooks     : full"

json=$("$DSH_CTL" app version --json)
python3 - "$json" <<'PY'
import json
import sys

data = json.loads(sys.argv[1])
assert data["dsh"] == "1.4.2", data
assert data["node"] == "v24.4.1", data
assert data["pnpm"] == "9.12.0", data
assert data["hooks"] == "full", data
assert data["source"] == "host", data
assert sorted(data["versions"]) == ["1.4.1", "1.4.2"], data
PY

json=$("$DSH_CTL" app deps --json)
python3 - "$json" <<'PY'
import json
import sys

data = json.loads(sys.argv[1])
assert data["node"] == "v24.4.1", data
assert data["pnpm"] == "9.12.0", data
assert data["hooks"] == "full", data
assert data["guard"] == "off", data
PY

json=$("$DSH_CTL" app plugins --json)
python3 - "$json" <<'PY'
import json
import sys

data = json.loads(sys.argv[1])
names = {item["name"]: item for item in data["items"]}
assert data["hooks_mode"] == "full", data
assert names["hooks.patch.yml"]["active"] is True, names
assert names["hooks-audit-only.patch.yml"]["active"] is False, names
assert names["phone-mcp"]["kind"] == "tool", names
PY

# exec is R1: needs an explicit confirmation and a command after --.
set +e
out=$("$DSH_CTL" app exec -- echo hi 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "app exec without confirmation returned $rc"
assert_contains "$out" "R1 requires DSH_RISK_CONFIRM=yes"

set +e
out=$(DSH_RISK_CONFIRM=yes "$DSH_CTL" app exec echo hi 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app exec without -- returned $rc"
assert_contains "$out" "command must follow --"

out=$(DSH_RISK_CONFIRM=yes "$DSH_CTL" app exec -- echo hi 2>&1) || fail "app exec failed: $out"
unset DSH_APP_PROBE_FILE

token=$("$DSH_CTL" risk issue --op app --target dsh-runtime \
  --reason 'operator switches dsh version' --impact 'changes the guest dsh runtime' --level R3)
[ "${#token}" -ge 16 ] || fail "risk issue did not return a token"

set +e
out=$(DSH_RISK_REASON='operator switches dsh version' "$DSH_CTL" app switch 1.4.1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "R3 app switch without a token returned $rc"
assert_contains "$out" "requires a valid one-time token"

# Still running -> refuse before touching any link.
set +e
out=$(DSH_RISK_REASON='operator switches dsh version' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app switch 1.4.1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || fail "app switch on a running chroot returned $rc"
assert_contains "$out" "必须先停止 chroot"
assert_eq "$(readlink "$DSH_ROOT/dsh/current")" "versions/1.4.2"

rm -f "$DSH_ROOT/state/guest/launcher.pid"
printf 'state=stopped\n' >"$DSH_ROOT/state/ctl/status"

set +e
out=$(DSH_RISK_REASON='operator switches dsh version' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app switch 1.4.1 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "app switch without --apply returned $rc"
assert_contains "$out" "--apply required"

out=$(DSH_RISK_REASON='operator switches dsh version' DSH_RISK_TOKEN="$token" \
  DSH_STEPS=1 "$DSH_CTL" app switch 1.4.1 --apply) || fail "app switch failed: $out"
assert_eq "$(readlink "$DSH_ROOT/dsh/current")" "versions/1.4.1"
assert_eq "$(readlink "$DSH_ROOT/dsh/previous")" "versions/1.4.2"
assert_contains "$out" "#step: 切换 current → versions/1.4.1"

json=$("$DSH_CTL" app list --json)
python3 - "$json" <<'PY'
import json
import sys

data = json.loads(sys.argv[1])
assert data["current"] == "versions/1.4.1", data
assert data["previous"] == "versions/1.4.2", data
assert sorted(data["versions"]) == ["1.4.1", "1.4.2"], data
PY

# Unknown or unsafe names never touch the symlink.
set +e
out=$(DSH_RISK_REASON='operator switches dsh version' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app switch 9.9.9 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app switch to an unknown version returned $rc"
assert_contains "$out" "version not installed"
assert_eq "$(readlink "$DSH_ROOT/dsh/current")" "versions/1.4.1"

out=$(DSH_RISK_REASON='operator switches dsh version' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app rollback --apply) || fail "app rollback failed: $out"
assert_eq "$(readlink "$DSH_ROOT/dsh/current")" "versions/1.4.2"
assert_eq "$(readlink "$DSH_ROOT/dsh/previous")" "versions/1.4.1"

rm -f "$DSH_ROOT/dsh/previous"
set +e
out=$(DSH_RISK_REASON='operator rolls back dsh version' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app rollback --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || fail "app rollback without previous returned $rc"
assert_contains "$out" "no previous version recorded"

# ---- app install -------------------------------------------------------
# R3 runs before argument validation, so the token tests come first.
set +e
out=$("$DSH_CTL" app install --from registry --version 9.9.9 --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "app install without a risk token succeeded"
assert_contains "$out" "R3 requires"

set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app install --from registry --version 9.9.9 2>&1)
rc=$?
set -e
[ "$rc" -eq 3 ] || fail "app install without --apply returned $rc"
assert_contains "$out" "--apply required"

set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app install --from floppy --version 9.9.9 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app install with an unknown source returned $rc"
assert_contains "$out" "registry|tarball|local"

set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app install --from tarball --url file:///tmp/dsh.tgz --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app install with a non-http url returned $rc"
assert_contains "$out" "must be http"

set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app install --from local --file /data/media/0/../../etc/passwd --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app install with .. in --file returned $rc"
assert_contains "$out" "must not contain .."

set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  "$DSH_CTL" app install --from registry --version 1.4.2 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || fail "app install over an existing version returned $rc"
assert_contains "$out" "already exists"

# Happy path with a fixture package (no guest involved): the holder must be running,
# because installing writes into the live /opt/dsh tree.
printf '%s\n' "$pid" >"$DSH_ROOT/state/guest/launcher.pid"
printf '%s\n' "$starttime" >"$DSH_ROOT/state/guest/launcher.starttime"
printf '%s\n' "$ns" >"$DSH_ROOT/state/guest/launcher.ns"
printf 'state=running\n' >"$DSH_ROOT/state/ctl/status"
pkgdir="$case_dir/pkg/1.5.0"
mkdir -p "$pkgdir/bin"
printf '#!/bin/sh\necho 1.5.0\n' >"$pkgdir/bin/dsh"
chmod 0755 "$pkgdir/bin/dsh"
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  DSH_APP_INSTALL_FIXTURE_DIR="$pkgdir" \
  "$DSH_CTL" app install --from local --file /data/local/tmp/pkg --version 1.5.0 --apply) ||
  fail "app install failed: $out"
assert_contains "$out" "installed : versions/1.5.0"
[ -x "$DSH_ROOT/dsh/versions/1.5.0/bin/dsh" ] || fail "app install did not publish versions/1.5.0"
[ ! -e "$DSH_ROOT/dsh/versions/.staging.$$" ] || fail "app install left a staging directory behind"
assert_contains "$(cat "$DSH_ROOT/dsh/versions/1.5.0/.dsh-install")" "source=local"

# A package without bin/dsh is rejected and leaves nothing behind.
badpkg="$case_dir/pkg/broken"
mkdir -p "$badpkg"
printf 'nothing here\n' >"$badpkg/README"
set +e
out=$(DSH_RISK_REASON='operator installs dsh' DSH_RISK_TOKEN="$token" \
  DSH_APP_INSTALL_FIXTURE_DIR="$badpkg" \
  "$DSH_CTL" app install --from local --file /data/local/tmp/broken --version 9.9.8 --apply 2>&1)
rc=$?
set -e
[ "$rc" -eq 4 ] || fail "app install of a package without bin/dsh returned $rc"
assert_contains "$out" "包内没有 bin/dsh"
[ ! -e "$DSH_ROOT/dsh/versions/9.9.8" ] || fail "failed app install published a version"
# ---- app packages（只读探测） -------------------------------------------
pkgs="$case_dir/packages.json"
cat >"$pkgs" <<'PKGJSON'
{"root":"/opt/dsh/current","pnpm_root":"/opt/dsh/current/node_modules","npm_root":"","dsh_name":"@deepseek-ai/dsh","dsh_version":"1.4.2","deps":[{"name":"commander","range":"^12.0.0"},{"name":"@deepseek-ai/dsh-hooks-claude-code","range":"0.8.1"}],"global":[{"name":"@deepseek-ai/dsh","version":"1.4.2"}],"plugins":["dsh-hooks-claude-code","dsh-permission-presets"],"hooks":"{\"mode\":\"full\"}"}
PKGJSON
out=$(DSH_APP_PACKAGES_FILE="$pkgs" "$DSH_CTL" app packages --json) || fail "app packages --json failed: $out"
python3 - <<'PYJSON' "$out"
import json, sys
data = json.loads(sys.argv[1])
assert data["dsh_version"] == "1.4.2", data
assert [d["name"] for d in data["deps"]] == ["commander", "@deepseek-ai/dsh-hooks-claude-code"], data
assert "dsh-hooks-claude-code" in data["plugins"], data
PYJSON
set +e
out=$("$DSH_CTL" app packages --bogus 2>&1)
rc=$?
set -e
[ "$rc" -eq 2 ] || fail "app packages with an unknown flag returned $rc"

# ---- app pin（改依赖范围，需已停） --------------------------------------
DSH_TEST_NODE=$(command -v node 2>/dev/null || true)
if [ -n "$DSH_TEST_NODE" ]; then
printf 'state=stopped\n' >"$DSH_ROOT/state/ctl/status"
pindir="$case_dir/pinroot"
mkdir -p "$pindir/current"
cat >"$pindir/current/package.json" <<'PKGJSON'
{"name":"@deepseek-ai/dsh","version":"1.4.2","dependencies":{"commander":"^12.0.0"},"optionalDependencies":{"fsevents":"^2.3.3"}}
PKGJSON
out=$(DSH_APP_RUNTIME_DIR="$pindir" DSH_APP_NODE="$DSH_TEST_NODE" "$DSH_CTL" app pin commander '^13.0.0' --apply) || fail "app pin failed: $out"
assert_contains "$out" "pinned: commander"
assert_contains "$out" "dependencies ^12.0.0 -> ^13.0.0"
grep -q '"\^13.0.0"' "$pindir/current/package.json" || fail "app pin did not write the new range"
ls "$pindir"/current/package.json.pin-bak.* >/dev/null 2>&1 || fail "app pin left no backup"

out=$(DSH_APP_RUNTIME_DIR="$pindir" DSH_APP_NODE="$DSH_TEST_NODE" "$DSH_CTL" app pin fsevents '^2.4.0' --apply) || fail "app pin on optional dependency failed: $out"
assert_contains "$out" "optionalDependencies ^2.3.3 -> ^2.4.0"

set +e
out=$(DSH_APP_RUNTIME_DIR="$pindir" DSH_APP_NODE="$DSH_TEST_NODE" "$DSH_CTL" app pin lodash '^4.17.21' --apply 2>&1)
rc=$?
[ "$rc" -eq 4 ] || fail "app pin on a non-dependency returned $rc"
assert_contains "$out" "not a dependency of this build"
out=$("$DSH_CTL" app pin commander '^13.0.0' 2>&1)
rc=$?
[ "$rc" -eq 3 ] || fail "app pin without --apply returned $rc"
out=$("$DSH_CTL" app pin 'commander; rm -rf /' '^1.0.0' --apply 2>&1)
rc=$?
[ "$rc" -eq 2 ] || fail "app pin with an injected name returned $rc"
out=$("$DSH_CTL" app pin commander '^1.0.0 && reboot' --apply 2>&1)
rc=$?
[ "$rc" -eq 2 ] || fail "app pin with an injected range returned $rc"
set -e
else
  printf 'skip: app pin tests need node\n' >&2
fi


printf 'test_app: ok\n'
