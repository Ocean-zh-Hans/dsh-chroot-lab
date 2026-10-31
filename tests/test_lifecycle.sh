#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

rm -f "$CASE/bin/setsid"
if ! command -v setsid >/dev/null 2>&1; then
  cat >"$CASE/bin/setsid" <<'EOF'
#!/bin/sh
exec "$@"
EOF
  chmod 0755 "$CASE/bin/setsid"
fi
export DSH_TEST_ROOT="$CASE"
export DSH_PROC=/proc
export DSH_MOUNTINFO="$CASE/proc/self/mountinfo"
export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"
export DSH_START_TIMEOUT=120
export DSH_STOP_TIMEOUT=120
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_ALLOW_SAME_NS=1

cat >"$CASE/bin/id" <<'EOF'
#!/bin/sh
echo 0
EOF

cat >"$CASE/bin/busybox" <<'EOF'
#!/bin/sh
last=
for arg in "$@"; do last=$arg; done
exec "$last"
EOF

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
_state=$DSH_TEST_ROOT/losetup.state
case "${1:-}" in
  -j)
    [ ! -f "$_state" ] || printf '%s: 0:0 (%s)\n' "$DSH_TEST_LOOP" "$DSH_IMAGE"
    ;;
  -f)
    printf '%s\n' "$DSH_TEST_LOOP" >"$_state"
    printf '%s\n' "$DSH_TEST_LOOP"
    ;;
  -d)
    [ "${DSH_TEST_DETACH_FAIL:-0}" != 1 ] || exit 1
    rm -f "$_state"
    ;;
  "$DSH_TEST_LOOP")
    if [ -f "$_state" ]; then exit 0; else exit 1; fi
    ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
exit 0
EOF

cat >"$CASE/bin/mount" <<'EOF'
#!/bin/sh
if [ "${DSH_TEST_BAKED_DSH:-1}" = 1 ]; then
  mkdir -p "$DSH_ROOTFS_DIR/opt/dsh/current/bin"
  printf '#!/bin/sh\n' >"$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
  chmod 0755 "$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
fi
mkdir -p "$DSH_ROOTFS_DIR$DSH_MODULE_DIR"
for target do :; done
if [ "${DSH_TEST_ROOTFS_ETC_LINK:-0}" = 1 ] && [ "$target" = "$DSH_ROOTFS_DIR" ]; then
  mkdir -p "$DSH_TEST_ROOT/host-etc"
  printf 'host-marker\n' >"$DSH_TEST_ROOT/host-etc/marker"
  rm -rf "$DSH_ROOTFS_DIR/etc"
  ln -s "$DSH_TEST_ROOT/host-etc" "$DSH_ROOTFS_DIR/etc"
fi
printf 'mount %s\n' "$*" >>"$DSH_TEST_ROOT/mounts.log"
for target do :; done
encoded=$(printf '%s' "$target" | awk '{ gsub(/ /, "\\\\040"); print }')
printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded" >>"$DSH_MOUNTINFO"
exit 0
EOF

cat >"$CASE/bin/umount" <<'EOF'
#!/bin/sh
printf 'umount %s\n' "$*" >>"$DSH_TEST_ROOT/umounts.log"
encoded=$(printf '%s' "$1" | awk '{ gsub(/ /, "\\\\040"); print }')
awk -v target="$encoded" '$5 != target' "$DSH_MOUNTINFO" >"$DSH_MOUNTINFO.tmp"
mv "$DSH_MOUNTINFO.tmp" "$DSH_MOUNTINFO"
if [ -n "${DSH_ROOTFS_DIR:-}" ] && [ "${1:-}" = "$DSH_ROOTFS_DIR" ]; then
  find "$DSH_ROOTFS_DIR" -mindepth 1 -delete
fi
exit 0
EOF

cat >"$CASE/bin/mountpoint" <<'EOF'
#!/bin/sh
for target do :; done
encoded=$(printf '%s' "$target" | awk '{ gsub(/ /, "\\\\040"); print }')
awk -v root="$encoded" '$5 == root { found = 1 } END { exit(found ? 0 : 1) }' "$DSH_MOUNTINFO"
EOF

cat >"$CASE/bin/chroot" <<'EOF'
#!/bin/sh
printf 'chroot %s\n' "$*" >>"$DSH_TEST_ROOT/chroot.log"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_ROOT/state/runtime/url"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_ROOT/state/runtime/health"
trap 'exit 0' TERM INT
while :; do sleep 0.2; done
EOF

cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--mount'; exit 0 ;;
  -t)
    if [ "${6:-}" = _scan-rootfs ]; then
      printf 'nsenter %s\n' "$*" >>"$DSH_TEST_ROOT/nsenter.log"
      exit 0
    fi
    ;;
esac
printf 'nsenter %s\n' "$*" >>"$DSH_TEST_ROOT/nsenter.log"
exit 0
EOF

chmod 0755 "$CASE/bin/id" "$CASE/bin/busybox" "$CASE/bin/losetup" "$CASE/bin/mount" \
  "$CASE/bin/umount" "$CASE/bin/mountpoint" "$CASE/bin/chroot" "$CASE/bin/nsenter"

start_ok() {
  set +e
  out=$("$DSH_CTL" start --apply "$@" 2>&1)
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    printf 'start rc=%s\n%s\n' "$rc" "$out"
    for f in "$DSH_ROOT/logs/nsinit.log" "$DSH_ROOT/logs/dsh-ctl.log"; do
      [ -r "$f" ] && {
        printf '\n-- %s --\n' "$f"
        cat "$f"
      }
    done
    return "$rc"
  fi
}

start_ok
assert_contains "$out" "started:"
assert_contains "$out" "url: http://127.0.0.1:1234/?token=test"

out=$("$DSH_CTL" status)
assert_contains "$out" "state        : running"
assert_contains "$out" "pid valid    : yes"
[ -s "$CASE/mounts.log" ] || fail "mount was not called"
grep -q 'ext4' "$CASE/mounts.log" || fail "rootfs ext4 mount missing"
grep -q -- '--rbind .*/host/data ' "$CASE/helper.log" || fail "data rbind missing"
grep -q -- '--make-rprivate ' "$CASE/helper.log" || fail "private propagation missing"
_expected_cleanup_ns=$(cat "$DSH_ROOT/state/guest/launcher.ns")

set +e
"$DSH_CTL" stop >"$TMPBASE/stop.out" 2>"$TMPBASE/stop.err"
_stop_rc=$?
set -e
if [ "$_stop_rc" -ne 0 ]; then
  printf 'stop rc=%s\n-- stdout --\n' "$_stop_rc"
  cat "$TMPBASE/stop.out" || true
  printf '\n-- stderr --\n'
  cat "$TMPBASE/stop.err" || true
  printf '\n-- state --\n'
  cat "$DSH_ROOT/state/ctl/status" 2>/dev/null || true
  printf '\n-- nsinit --\n'
  cat "$DSH_ROOT/logs/nsinit.log" 2>/dev/null || true
  exit 1
fi
out=$("$DSH_CTL" status)
assert_contains "$out" "state        : stopped"
grep -F -- "_scan-rootfs $_expected_cleanup_ns" "$CASE/nsenter.log" >/dev/null || fail "cleanup did not use the holder namespace scan"
[ ! -e "$DSH_ROOT/state/guest/launcher.pid" ] || fail "pid file not cleared"
[ ! -e "$DSH_ROOT/state/guest/launcher.ns" ] || fail "ns file not cleared"
[ ! -e "$CASE/losetup.state" ] || fail "loop is still attached"
[ ! -e "$DSH_TMP/dsh-recovery" ] || fail "recovery state was not cleared"
grep -Eq -- '--mount=[^ ]+ -- chroot ' "$CASE/nsenter.log" || fail "nsenter command separator missing"

start_ok
assert_contains "$out" "started:"
cat >"$CASE/bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --mount=*) exit 1 ;;
esac
printf 'nsenter %s\n' "$*" >>"$DSH_TEST_ROOT/nsenter.log"
exit 0
EOF
chmod 0755 "$CASE/bin/nsenter"
export DSH_TEST_DETACH_FAIL=1
set +e
out=$("$DSH_CTL" stop 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "detach failure returned success"
assert_contains "$out" "cleanup incomplete"
assert_contains "$("$DSH_CTL" status)" "state        : recovery-required"
grep -Eq -- '-t [0-9]+ -m -- chroot ' "$CASE/nsenter.log" || fail "nsenter fallback or separator missing"
[ -e "$DSH_ROOT/state/guest/loopdev" ] || fail "detach failure discarded the loop record"
[ -e "$CASE/losetup.state" ] || fail "detach failure fake lost its state"
unset DSH_TEST_DETACH_FAIL
out=$("$DSH_CTL" recover --apply)
assert_contains "$out" "recover: ok"
assert_contains "$("$DSH_CTL" status)" "state        : stopped"
[ ! -e "$DSH_ROOT/state/guest/loopdev" ] || fail "recovery retained a detached loop record"

cat >"$CASE/bin/e2fsck" <<'EOF'
#!/bin/sh
exit 2
EOF
chmod 0755 "$CASE/bin/e2fsck"
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "failed start returned success"
[ ! -e "$DSH_ROOT/state/ctl/selinux.orig" ] || fail "SELinux restore marker was retained"

cat >"$CASE/bin/getprop" <<'EOF'
#!/bin/sh
case ${1:-} in
  sys.boot_completed) printf '1\n' ;;
  sys.user.0.ce_available) printf 'true\n' ;;
esac
exit 0
EOF
cat >"$CASE/bin/e2fsck" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 0755 "$CASE/bin/getprop" "$CASE/bin/e2fsck"
printf '1\n' >"$DSH_ROOT/state/ctl/autostart"
cat >"$CASE/bin/chroot" <<'EOF'
#!/bin/sh
printf 'chroot %s\n' "$*" >>"$DSH_TEST_ROOT/chroot.log"
printf 'http://127.0.0.1:1234/?token=test\n' >"$DSH_ROOT/state/runtime/url"
printf 'ok:%s\n' "$(date +%s)" >"$DSH_ROOT/state/runtime/health"
trap 'sleep 12; exit 0' TERM INT
while :; do sleep 0.2; done
EOF
chmod 0755 "$CASE/bin/chroot"
cat >"$CASE/bin/chmod" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/chmod.log"
exec /usr/bin/chmod "$@"
EOF
chmod 0755 "$CASE/bin/chmod"
out=$("$DSH_CTL" boot 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "started:"
assert_eq "$(cat "$DSH_ROOT/state/ctl/boot-fails")" "0"
"$DSH_CTL" stop >/dev/null

printf '%s\n' "$$" >"$DSH_ROOT/state/guest/launcher.pid"
printf '1\n' >"$DSH_ROOT/state/guest/launcher.starttime"
readlink "/proc/$$/ns/mnt" >"$DSH_ROOT/state/guest/launcher.ns"
printf 'previous-boot\n' >"$DSH_ROOT/state/guest/launcher.bootid"
printf '%s\n' "$DSH_TEST_LOOP" >"$DSH_ROOT/state/guest/loopdev"
printf 'state=recovery-required\nnote=identity-mismatch\n' >"$DSH_ROOT/state/ctl/status"
out=$("$DSH_CTL" boot 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "started:"
[ "$(cat "$DSH_ROOT/state/guest/launcher.pid")" != "$$" ] || fail "boot reused a stale pid"
assert_eq "$(cat "$DSH_ROOT/state/guest/launcher.bootid")" "$(cat "$DSH_BOOT_ID_SOURCE")"
"$DSH_CTL" stop >/dev/null

rm -f "$DSH_MODULE_DIR/customize.sh"
export DSH_SELINUXFS_MODE=ro
start_ok
assert_contains "$out" "started:"
grep -q 'state/runtime .*run/dsh' "$CASE/helper.log" || fail "guest runtime directory was not bound"
if grep -q "state/guest $DSH_ROOTFS_DIR/run/dsh\$" "$CASE/helper.log"; then
  fail "holder identity directory was exposed to the guest"
fi
python3 - "$DSH_ROOT/state/guest/module.json" <<'PY'
import json, sys
metadata = json.load(open(sys.argv[1]))
assert metadata['version'] == '0.2.2'
assert metadata['versionCode'] == 4
assert metadata['autostart'] is True
assert metadata['selinuxfsMode'] == 'ro'
assert metadata['blockAccess'] == 'ro'
assert metadata['startedAt'] > 0
PY
[ -f "$DSH_ROOTFS_DIR/run/dsh/module.json" ] || fail 'read-only module metadata was not published'
[ -f "$DSH_ROOTFS_DIR/run/dsh/module.sha256" ] || fail 'module script hashes were not published'
grep -q -- '--remount-ro-bind .*run/dsh/module.json' "$CASE/helper.log" || fail 'module metadata was not read-only'
grep -F "$DSH_MODULE_DIR" "$CASE/helper.log" >/dev/null || fail 'custom module path was not read-only bound'
[ -d "$DSH_ROOTFS_DIR/var/tmp" ] || fail 'disk-backed guest TMPDIR was not created'
grep -F "1777 $DSH_ROOTFS_DIR/var/tmp" "$CASE/chmod.log" >/dev/null || fail 'disk-backed guest TMPDIR was not made 1777'
grep -q 'TMPDIR=/var/tmp' "$CASE/chroot.log" || fail 's6 did not receive the disk-backed TMPDIR'
grep -q 'mount -t selinuxfs -o ro selinuxfs ' "$CASE/mounts.log" || {
  cat "$CASE/mounts.log"
  fail "read-only selinuxfs mount missing"
}
"$DSH_CTL" stop >/dev/null
chmod 0700 "$DSH_ROOT/state/workspace"
before_mode=$(/usr/bin/stat -c %a "$DSH_ROOT/state/workspace")
start_ok
assert_contains "$out" "started:"
grep -q 'DSH_WORKSPACE_DIR=/root/workspace' "$CASE/chroot.log" || fail 'independent root workspace was not selected'
grep -F -- "--bind $DSH_ROOT/state/workspace $DSH_ROOTFS_DIR/root/workspace" "$CASE/helper.log" >/dev/null || fail 'independent workspace was not bound'
[ ! -e "$DSH_ROOTFS_DIR/root/.dsh/workspace" ] || fail 'workspace remained inside DSH home'
assert_eq "$(/usr/bin/stat -c %a "$DSH_ROOT/state/workspace")" "$before_mode"
if grep -F "2777 $DSH_ROOT/state/workspace" "$CASE/chmod.log" >/dev/null; then
  fail 'independent workspace permissions were broadened'
fi
"$DSH_CTL" stop >/dev/null
mkdir -p "$DSH_ROOT/state/dsh-home/workspace"
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'legacy DSH-home workspace was accepted'
assert_contains "$out" 'legacy'
rm -rf "$DSH_ROOT/state/dsh-home/workspace"
export DSH_WORKSPACE_DIR=/root/custom-workspace
start_ok
assert_contains "$out" "started:"
grep -F -- "--bind $DSH_ROOT/state/workspace $DSH_ROOTFS_DIR/root/custom-workspace" "$CASE/helper.log" >/dev/null || fail 'custom guest workspace was not bound from independent storage'
"$DSH_CTL" stop >/dev/null
unset DSH_WORKSPACE_DIR
printf 'keep-permission-content\n' >"$DSH_ROOT/state/workspace/marker"
out=$("$DSH_CTL" fixperm)
assert_contains "$out" "$DSH_ROOT/state/workspace"
assert_eq "$(cat "$DSH_ROOT/state/workspace/marker")" 'keep-permission-content'
[ ! -e "$DSH_MEDIA/DSH/workspace" ] || fail 'fixperm recreated the legacy sdcard workspace'
unset DSH_SELINUXFS_MODE
export DSH_SELINUXFS_MODE=invalid
set +e
"$DSH_CTL" plan >/dev/null 2>&1
rc=$?
set -e
unset DSH_SELINUXFS_MODE
[ "$rc" -ne 0 ] || fail "invalid SELinuxFS mode was accepted"

grep -q -- '--rbind .*dev/block' "$CASE/helper.log" || fail "default /dev/block bind missing"
grep -q -- '--remount-ro .*dev/block' "$CASE/helper.log" || fail "default /dev/block read-only bind missing"
export DSH_BLOCK_ACCESS=full
before=$(grep -c 'dev/block' "$CASE/mounts.log" || true)
start_ok
assert_contains "$out" "started:"
after=$(grep -c 'dev/block' "$CASE/mounts.log" || true)
[ "$after" = "$before" ] || fail "full block access added a mask"
"$DSH_CTL" stop >/dev/null
unset DSH_BLOCK_ACCESS
export DSH_TEST_BLOCK_LINK=1
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
unset DSH_TEST_BLOCK_LINK
[ "$rc" -ne 0 ] || fail "symlink /dev/block was accepted"
grep -q 'refusing to read-only bind a /dev/block symlink' "$DSH_ROOT/logs/nsinit.log" || fail "symlink guard did not fail safely"
"$DSH_CTL" stop >/dev/null
export DSH_BLOCK_ACCESS=invalid
set +e
"$DSH_CTL" plan >/dev/null 2>&1
rc=$?
set -e
unset DSH_BLOCK_ACCESS
[ "$rc" -ne 0 ] || fail "invalid block access mode was accepted"
export DSH_TEST_BAKED_DSH=0
rm -f "$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh" "$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh.off"
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
unset DSH_TEST_BAKED_DSH
[ "$rc" -ne 0 ] || fail "start accepted a missing DSH executable"
grep -q 'DSH executable is missing from host and baked runtimes' "$DSH_ROOT/logs/nsinit.log" || fail "missing runtime was not reported"
"$DSH_CTL" stop >/dev/null

# P2-2: a rootfs /etc symlink must not redirect resolv.conf/hosts writes into
# the host. The fake ext4 mount plants /etc -> host-etc after the rootfs mount.
export DSH_TEST_ROOTFS_ETC_LINK=1
set +e
out=$("$DSH_CTL" start --apply 2>&1)
rc=$?
set -e
unset DSH_TEST_ROOTFS_ETC_LINK
[ "$rc" -ne 0 ] || fail 'rootfs /etc symlink was accepted'
assert_eq "$(cat "$CASE/host-etc/marker")" 'host-marker'
[ ! -e "$CASE/host-etc/resolv.conf" ] || fail 'rootfs symlink wrote resolv.conf into host'
[ ! -e "$CASE/host-etc/hosts" ] || fail 'rootfs symlink wrote hosts into host'
grep -q 'refusing symlinked /etc' "$DSH_ROOT/logs/nsinit.log" || fail 'rootfs symlink guard did not fail safely'
"$DSH_CTL" stop >/dev/null

printf 'test_lifecycle: ok\n'
