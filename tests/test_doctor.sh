#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

out=$("$DSH_CTL" doctor 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "rootfs image is private"
assert_contains "$out" "recursive-ro helper present"
assert_contains "$out" "Summary:"

out=$("$DSH_CTL" doctor --for-apply 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "Mode: apply-readiness"
assert_contains "$out" "Summary:"

out=$(DSH_TEST_IMAGE_SIZE=137438953472 "$DSH_CTL" doctor --for-apply 2>&1) || {
  printf '%s\n' "$out"
  exit 1
}
assert_contains "$out" "rootfs image size: 137438953472 bytes"
set +e
out=$(DSH_TEST_IMAGE_SIZE=0 "$DSH_CTL" doctor 2>&1)
rc=$?
set -e
assert_eq "$rc" 4
assert_contains "$out" "rootfs image size is zero/unreadable"

# A rootfs mountpoint under /data is a self-reference hazard and must FAIL.
set +e
out=$(DSH_TEST_ALLOW_UNUSUAL_PATHS=0 DSH_ROOTFS_DIR=/data/dsh-rootfs "$DSH_CTL" doctor 2>&1)
rc=$?
set -e
assert_eq "$rc" 4
assert_contains "$out" "rootfs mountpoint must not be under"

ksud="$case_dir/ksud"
cat >"$ksud" <<'EOF'
#!/bin/sh
cat "$DSH_TEST_KSU_FEATURES"
EOF
chmod 0755 "$ksud"
features="$case_dir/ksu-features"
cat >"$features" <<'EOF'
[ENABLED (1)] su_compat (ID=0)
[ENABLED (1)] kernel_umount (ID=1)
[DISABLED] adb_root (ID=3)
[ENABLED (1)] selinux_hide (ID=4)
[ENABLED (1)] mount_hide (ID=16)
[ENABLED (1)] avc_spoof (ID=10003)
EOF
. ./module/lib/doctor.sh
id() {
  if [ "${1:-}" = -u ]; then printf '0\n'; else command id "$@"; fi
}
DSH_KSUD=$ksud
DSH_TEST_KSU_FEATURES=$features
export DSH_TEST_KSU_FEATURES
DSH_DOC_PASS=0
DSH_DOC_WARN=0
DSH_DOC_FAIL=0
dsh_doc_check_ksu
assert_eq "$DSH_DOC_PASS" 5
assert_eq "$DSH_DOC_WARN" 0
assert_eq "$DSH_DOC_FAIL" 0
if dsh_doc_ksu_feature_enabled adb_root "$(cat "$features")"; then
  fail "disabled adb_root was accepted"
fi
printf '%s\n' 'su_compat: ENABLED' 'kernel_umount: enabled' 'selinux_hide: ENABLED' \
  'mount_hide: ENABLED' 'avc_spoof: ENABLED' >"$features"
DSH_DOC_PASS=0
DSH_DOC_WARN=0
DSH_DOC_FAIL=0
dsh_doc_check_ksu
assert_eq "$DSH_DOC_PASS" 5
assert_eq "$DSH_DOC_FAIL" 0
printf '%s\n' 'avc_spoof: DISABLED' >>"$features"
set +e
dsh_doc_check_ksu >/dev/null
set -e
assert_eq "$DSH_DOC_FAIL" 1

mv "$DSH_ROOT/control/bin/dsh-run" "$DSH_ROOT/control/bin/dsh-run.off"
set +e
out=$("$DSH_CTL" doctor --for-apply 2>&1)
rc=$?
set -e
mv "$DSH_ROOT/control/bin/dsh-run.off" "$DSH_ROOT/control/bin/dsh-run"
assert_eq "$rc" 4
assert_contains "$out" "dsh-run is missing or not executable"
cat >"$CASE/bin/debugfs" <<'EOF'
#!/bin/sh
case "${2:-}" in
  'stat /usr/bin/python3') printf 'Mode: 0755 Type: regular\n' ;;
  'stat /tmp') printf 'Mode: 1777 Type: directory\n' ;;
  *) exit 1 ;;
esac
EOF
chmod 0755 "$CASE/bin/debugfs"
set +e
out=$("$DSH_CTL" doctor --for-apply 2>&1)
rc=$?
set -e
assert_eq "$rc" 4
assert_contains "$out" "host and baked DSH executables are unavailable"

printf 'test_doctor: ok\n'
