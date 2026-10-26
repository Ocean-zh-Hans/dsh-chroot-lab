#!/usr/bin/env bash
# Shared test helpers. Tests are local-only and must never touch /data.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
MODULE="$ROOT/module"
DSH_CTL="$MODULE/bin/dsh-ctl"
TMPBASE=$(mktemp -d "${TMPDIR:-/tmp}/dsh-tests.XXXXXX")
trap '[ "${DSH_TEST_KEEP:-0}" = 1 ] || rm -rf "$TMPBASE"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  [ "$1" = "$2" ] || fail "expected [$2], got [$1]"
}

assert_contains() {
  case "$1" in
  *"$2"*) ;;
  *) fail "expected output to contain [$2], got:\n$1" ;;
  esac
}

new_case() {
  CASE=$(mktemp -d "$TMPBASE/case.XXXXXX")
  mkdir -p "$CASE"
  printf '%s\n' "$CASE"
}

fake_bin() {
  _bin=$1
  mkdir -p "$_bin"
  for _c in busybox chroot mount umount nsenter setsid sha256sum sync mke2fs e2fsck resize2fs; do
    cat >"$_bin/$_c" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod 0755 "$_bin/$_c"
  done
  cat >"$_bin/nsenter" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--mount' ;;
esac
exit 0
EOF
  cat >"$_bin/unshare" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--mount --propagation'; exit 0 ;;
esac
last=
for arg in "$@"; do last=$arg; done
exec "$last"
EOF
  cat >"$_bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--sector-size --direct-io' ;;
esac
exit 0
EOF
  chmod 0755 "$_bin/nsenter" "$_bin/unshare" "$_bin/losetup"
}

fixture_module() {
  _dst=$1
  cp -a "$MODULE" "$_dst"
  mkdir -p "$_dst/tools/bin"
  cat >"$_dst/tools/bin/dsh-mount-ro-recursive" <<'EOF'
#!/bin/sh
if [ -n "${DSH_TEST_ROOT:-}" ]; then
  printf 'helper %s\n' "$*" >>"$DSH_TEST_ROOT/helper.log" 2>/dev/null || true
fi
exit 0
EOF
  chmod 0755 "$_dst/tools/bin/dsh-mount-ro-recursive"
}

fixture_env() {
  CASE=$1
  export DSH_ROOT="$CASE/root"
  export DSH_MODULE_DIR="$CASE/module"
  export DSH_IMAGE_DIR="$CASE/media/ubuntu-rootfs"
  export DSH_IMAGE="$DSH_IMAGE_DIR/rootfs.img"
  export DSH_ROOTFS_DIR="$CASE/mnt"
  export DSH_MEDIA="$CASE/media"
  export DSH_TMP="$CASE/tmp"
  export DSH_PROC="$CASE/proc"
  export DSH_SYS="$CASE/sys"
  export DSH_TEST_ALLOW_NONROOT=1
  export DSH_TEST_ALLOW_UNUSUAL_PATHS=1
  export PATH="$CASE/bin:$PATH"

  mkdir -p "$DSH_ROOT/control" "$DSH_ROOT/state/ctl" "$DSH_ROOT/state/guest" \
    "$DSH_ROOT/logs" "$DSH_ROOT/dsh" "$DSH_IMAGE_DIR" "$DSH_TMP" "$DSH_PROC/self/attr" \
    "$DSH_SYS/fs/selinux" "$DSH_ROOTFS_DIR" "$CASE/bin"
  chmod 0700 "$DSH_ROOT" "$DSH_IMAGE_DIR"
  printf '1\n' >"$DSH_SYS/fs/selinux/enforce"
  printf 'u:r:ksu:s0\n' >"$DSH_PROC/self/attr/current"
  printf '0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 hidepid=invisible\n' >"$DSH_PROC/self/mountinfo"
  printf 'x' >"$DSH_IMAGE"
  chmod 0600 "$DSH_IMAGE"
  : >"$DSH_IMAGE_DIR/.nomedia"
  fake_bin "$CASE/bin"
}
