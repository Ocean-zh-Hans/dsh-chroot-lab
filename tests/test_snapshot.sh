#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

# P2-4: symlink entries must not escape the state archive root. GNU tar
# reports "name -> target"; the target must be normalized relative to the
# entry's directory and remain under state/.
mkdir -p "$CASE/bad-archive/state/dsh-home"
ln -s /etc/passwd "$CASE/bad-archive/state/dsh-home/evil"
tar -czf "$CASE/bad-archive.tar.gz" -C "$CASE/bad-archive" state
set +e
out=$(sh -c '. "$DSH_MODULE_DIR/lib/common.sh"; . "$DSH_MODULE_DIR/lib/snapshot.sh"; dsh_snapshot_validate_archive "$1"' sh "$CASE/bad-archive.tar.gz" 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "snapshot accepted an absolute symlink target"
assert_contains "$out" "unsafe paths"

mkdir -p "$CASE/good-archive/state/dsh-home" "$CASE/good-archive/state/workspace"
ln -s ../workspace "$CASE/good-archive/state/dsh-home/link"
tar -czf "$CASE/good-archive.tar.gz" -C "$CASE/good-archive" state
sh -c '. "$DSH_MODULE_DIR/lib/common.sh"; . "$DSH_MODULE_DIR/lib/snapshot.sh"; dsh_snapshot_validate_archive "$1"' sh "$CASE/good-archive.tar.gz" >/dev/null

printf 'abc' >"$DSH_IMAGE"
printf 'before' >"$DSH_ROOT/state/dsh-home/setting"
printf 'workspace-before\n' >"$DSH_ROOT/state/workspace/marker"
printf '{"version":"old"}\n' >"$DSH_ROOT/state/guest/module.json"
printf 'old-module-hash\n' >"$DSH_ROOT/state/guest/module.sha256"
chmod 0600 "$DSH_IMAGE"
out=$("$DSH_CTL" snapshot create s1 --last-good)
assert_contains "$out" "snapshot:"
assert_eq "$(cat "$DSH_ROOT/state/ctl/last-good")" "s1"
[ -f "$DSH_IMAGE_DIR/snapshots/rootfs-s1.img" ] || fail "image snapshot missing"
[ -f "$DSH_ROOT/snapshots/state-s1.tar.gz" ] || fail "state snapshot missing"
manifest="$DSH_ROOT/snapshots/rootfs-s1.manifest"
archive="$DSH_ROOT/snapshots/state-s1.tar.gz"
cp "$manifest" "$CASE/manifest.good"
cp "$archive" "$CASE/archive.good"

out=$("$DSH_CTL" snapshot list)
assert_contains "$out" "rootfs-s1.img"
assert_contains "$out" "state-s1.tar.gz"
assert_contains "$out" "last-good: s1"
json=$("$DSH_CTL" snapshot list --json)
python3 - <<'PYJSON' "$json"
import json, sys
data = json.loads(sys.argv[1])
names = [item["name"] for item in data["snapshots"]]
assert "s1" in names, names
PYJSON
out=$("$DSH_CTL" snapshot promote s1)
assert_contains "$out" "last-good: s1"

printf 'changed' >"$DSH_IMAGE"
printf 'after' >"$DSH_ROOT/state/dsh-home/setting"
mkdir -p "$DSH_TMP/dsh-recovery"
printf '%s\n' "$DSH_ROOT" >"$DSH_TMP/dsh-recovery/owner"
out=$("$DSH_CTL" restore s1 --apply)
assert_contains "$out" "restored image"
assert_eq "$(cat "$DSH_IMAGE")" "abc"
assert_eq "$(cat "$DSH_ROOT/state/dsh-home/setting")" "before"
assert_eq "$(cat "$DSH_ROOT/state/workspace/marker")" 'workspace-before'
[ ! -e "$DSH_TMP/dsh-recovery" ] || fail "restore retained the recovery directory"
[ ! -e "$DSH_ROOT/state/guest/module.json" ] || fail "restore revived old module metadata"
[ ! -e "$DSH_ROOT/state/guest/module.sha256" ] || fail "restore revived old module hashes"
mkdir "$CASE/legacy"
tar -xzf "$CASE/archive.good" -C "$CASE/legacy"
mv "$CASE/legacy/state/workspace" "$CASE/legacy/state/dsh-home/workspace"
printf 'legacy-workspace\n' >"$CASE/legacy/state/dsh-home/workspace/marker"
tar -czf "$archive" -C "$CASE/legacy" state
legacy_hash=$(sha256sum "$archive" | awk '{print $1}')
python3 - "$manifest" "$legacy_hash" <<'PY'
import sys
path, digest = sys.argv[1:]
with open(path) as f:
    lines = f.readlines()
with open(path, 'w') as f:
    for line in lines:
        f.write('state_sha256=' + digest + '\n' if line.startswith('state_sha256=') else line)
PY
out=$("$DSH_CTL" restore s1 --apply)
assert_contains "$out" 'restored image'
assert_eq "$(cat "$DSH_ROOT/state/workspace/marker")" 'legacy-workspace'
[ ! -e "$DSH_ROOT/state/dsh-home/workspace" ] || fail 'legacy snapshot restored a workspace inside DSH home'
cp "$CASE/archive.good" "$archive"
cp "$CASE/manifest.good" "$manifest"
set +e
"$DSH_CTL" restore s1 >/dev/null 2>&1
rc=$?
set -e
assert_eq "$rc" 3

cat >"$CASE/bin/ln" <<'EOF'
#!/bin/sh
exit 1
EOF
cat >"$DSH_MODULE_DIR/tools/bin/cp" <<'EOF'
#!/bin/sh
if [ "${1:-}" = --help ]; then exec /usr/bin/cp --help; fi
/usr/bin/cp "$@" || exit $?
case "${3:-}" in
  */rootfs-pre-restore-*.img) printf X | /usr/bin/dd of="$3" bs=1 seek=0 conv=notrunc 2>/dev/null ;;
esac
EOF
chmod 0755 "$CASE/bin/ln" "$DSH_MODULE_DIR/tools/bin/cp"
printf 'keep-backup-mismatch' >"$DSH_IMAGE"
set +e
out=$("$DSH_CTL" restore s1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "corrupt previous-image backup was accepted"
assert_eq "$(cat "$DSH_IMAGE")" "keep-backup-mismatch"
assert_contains "$out" "previous-image backup checksum mismatch"
rm -f "$DSH_IMAGE_DIR"/rootfs-pre-restore-*.img
cat >"$DSH_MODULE_DIR/tools/bin/cp" <<'EOF'
#!/bin/sh
exec /usr/bin/cp "$@"
EOF
chmod 0755 "$DSH_MODULE_DIR/tools/bin/cp"

REAL_MV=$(command -v mv)
export REAL_MV
cat >"$CASE/bin/mv" <<'EOF'
#!/bin/sh
case "${1:-}" in
  */new/state/guest)
    for backup in "$DSH_IMAGE_DIR"/rootfs-pre-restore-*.img; do
      [ -f "$backup" ] || continue
      printf X | /usr/bin/dd of="$backup" bs=1 seek=0 conv=notrunc 2>/dev/null
    done
    exit 1
    ;;
esac
exec "$REAL_MV" "$@"
EOF
chmod 0755 "$CASE/bin/mv"
printf 'keep-corrupt-rollback' >"$DSH_IMAGE"
set +e
out=$("$DSH_CTL" restore s1 --apply 2>&1)
rc=$?
set -e
assert_eq "$rc" 4
assert_eq "$(cat "$DSH_IMAGE")" "abc"
assert_contains "$out" "rollback image checksum mismatch"
assert_contains "$("$DSH_CTL" status)" "state        : recovery-required"
rm -f "$CASE/bin/mv" "$CASE/bin/ln" "$DSH_IMAGE_DIR"/rootfs-pre-restore-*.img
printf 'abc' >"$DSH_IMAGE"

rm "$manifest"
printf 'keep-missing-manifest' >"$DSH_IMAGE"
set +e
out=$("$DSH_CTL" restore s1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "restore accepted a missing manifest"
assert_eq "$(cat "$DSH_IMAGE")" "keep-missing-manifest"
assert_eq "$(cat "$DSH_ROOT/state/dsh-home/setting")" "before"
cp "$CASE/manifest.good" "$manifest"

printf 'broken archive' >"$archive"
printf 'keep-bad-state' >"$DSH_IMAGE"
set +e
out=$("$DSH_CTL" restore s1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "restore accepted a corrupt state archive"
assert_eq "$(cat "$DSH_IMAGE")" "keep-bad-state"
cp "$CASE/archive.good" "$archive"

REAL_MV=$(command -v mv)
export REAL_MV
cat >"$CASE/bin/mv" <<'EOF'
#!/bin/sh
case "${1:-}" in
  */new/state/guest) exit 1 ;;
esac
exec "$REAL_MV" "$@"
EOF
chmod 0755 "$CASE/bin/mv"
printf 'original-before-state-fail' >"$DSH_IMAGE"
printf 'state-before-fail' >"$DSH_ROOT/state/dsh-home/setting"
set +e
out=$("$DSH_CTL" restore s1 --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "state failure returned success"
assert_eq "$(cat "$DSH_IMAGE")" "original-before-state-fail"
assert_eq "$(cat "$DSH_ROOT/state/dsh-home/setting")" "state-before-fail"
rm -f "$CASE/bin/mv"

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) printf '/dev/loop77: 0:0 (%s)\n' "$2" ;;
  -d) exit 1 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
chmod 0755 "$CASE/bin/losetup"
set +e
out=$("$DSH_CTL" purge --yes --keep-image 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "purge succeeded with an attached image"
[ -d "$DSH_ROOT" ] || fail "failed purge removed the data root"
[ -f "$DSH_IMAGE" ] || fail "failed purge removed the image"
[ ! -e "$DSH_ROOT/state/ctl/watchdog.disabled" ] || fail "failed purge left watchdog disabled"
[ ! -e "$DSH_ROOT/state/ctl/purge.in-progress" ] || fail "failed purge left its operation marker"

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) exit 0 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 1 ;;
esac
EOF
chmod 0755 "$CASE/bin/losetup"
export DSH_TEST_ROOT="$CASE"
REAL_RM=$(command -v rm)
export REAL_RM
cat >"$CASE/bin/rm" <<'EOF'
#!/bin/sh
if [ "${1:-}" = -rf ] && [ "${2:-}" = "$DSH_ROOT" ]; then
  [ -d "$DSH_ROOT/state/ctl/lock" ] || {
    printf 'missing\n' >"$DSH_TEST_ROOT/purge-lock"
    exit 90
  }
  printf 'held\n' >"$DSH_TEST_ROOT/purge-lock"
  : >"$DSH_TEST_ROOT/purge-started"
  sleep 5
fi
exec "$REAL_RM" "$@"
EOF
chmod 0755 "$CASE/bin/rm"
export DSH_PROC=/proc
export DSH_TEST_SKIP_NS_SCAN=1
"$DSH_CTL" purge --yes --keep-image >"$CASE/purge.out" 2>&1 &
PURGE_PID=$!
i=0
while [ "$i" -lt 1200 ] && [ ! -e "$CASE/purge-started" ] && kill -0 "$PURGE_PID" 2>/dev/null; do
  sleep 0.1
  i=$((i + 1))
done
[ -e "$CASE/purge-started" ] || {
  cat "$CASE/purge.out"
  fail "purge did not reach its locked delete step"
}
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "a new start entered during purge"
[ ! -e "$DSH_ROOT" ] || fail "start did not wait for the locked purge to finish"
set +e
wait "$PURGE_PID"
rc=$?
set -e
assert_eq "$rc" 0
assert_eq "$(cat "$CASE/purge-lock")" "held"
assert_contains "$(cat "$CASE/purge.out")" "purged:"
[ ! -e "$DSH_ROOT" ] || fail "purge did not remove data root"
[ -f "$DSH_IMAGE" ] || fail "purge --keep-image removed image"

python3 - "$CASE" <<'PY'
import io
import sys
import tarfile

case = sys.argv[1]

def make(name, members):
    with tarfile.open(case + "/" + name + ".tgz", "w:gz") as archive:
        for path, kind, link in members:
            info = tarfile.TarInfo(path)
            info.type = kind
            info.linkname = link
            if kind == tarfile.REGTYPE:
                info.size = 1
                archive.addfile(info, io.BytesIO(b"x"))
            else:
                archive.addfile(info)

make("safe-links", [
    ("state/dsh-home/file", tarfile.REGTYPE, ""),
    ("state/workspace", tarfile.DIRTYPE, ""),
    ("state/dsh-home/relative-link", tarfile.SYMTYPE, "../workspace"),
    ("state/dsh-home/hard", tarfile.LNKTYPE, "state/dsh-home/file"),
])
make("unsafe-absolute", [("/escape", tarfile.REGTYPE, "")])
make("unsafe-parent", [("state/../../escape", tarfile.REGTYPE, "")])
make("unsafe-absolute-link", [
    ("state/dsh-home/absolute-link", tarfile.SYMTYPE, "/etc/passwd"),
])
make("unsafe-parent-link", [
    ("state/dsh-home/parent-link", tarfile.SYMTYPE, "../../outside"),
])
make("unsafe-link-name", [
    ("state/file -> ../../escape", tarfile.REGTYPE, ""),
    ("state/hard link to ../escape", tarfile.REGTYPE, ""),
])
PY
. "$DSH_MODULE_DIR/lib/common.sh"
. "$DSH_MODULE_DIR/lib/snapshot.sh"
dsh_snapshot_validate_archive "$CASE/safe-links.tgz"
for archive in unsafe-absolute unsafe-parent unsafe-absolute-link unsafe-parent-link unsafe-link-name; do
  if dsh_snapshot_validate_archive "$CASE/$archive.tgz"; then
    fail "$archive was accepted"
  fi
done
. "$DSH_MODULE_DIR/lib/config.sh"
dsh_config_init
mkdir -p "$DSH_CTL_DIR"
: >"$DSH_PURGE_MARKER"
: >"$DSH_WATCHDOG_DISABLED"
DSH_WATCHDOG=/bin/false
set +e
dsh_purge_restore_watchdog 0
rc=$?
set -e
assert_eq "$rc" 1
[ -e "$DSH_WATCHDOG_DISABLED" ] || fail "failed watchdog enable cleared the disabled marker"

printf 'test_snapshot: ok\n'
