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
  cat >"$_bin/ls" <<'EOF'
#!/bin/sh
rc=0
opts=
for arg do
  case $arg in
    -*) opts="$opts $arg" ;;
    *'[0-9]*'/ns/mnt) printf 'lrwxrwxrwx 1 root root 0 Jan 1 00:00 %s/1/ns/mnt -> mnt:[4026531840]\n' "$DSH_PROC" ;;
    *'[0-9]*'/root) printf 'lrwxrwxrwx 1 root root 0 Jan 1 00:00 %s/1/root -> /\n' "$DSH_PROC" ;;
    *) /usr/bin/ls $opts "$arg" 2>/dev/null || rc=$? ;;
  esac
done
exit "$rc"
EOF
  chmod 0755 "$_bin/ls"
  for _c in busybox chroot mount umount nsenter setsid sync mke2fs e2fsck resize2fs true; do
    cat >"$_bin/$_c" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod 0755 "$_bin/$_c"
  done
  cat >"$_bin/mount" <<'EOF'
#!/bin/sh
if [ "${DSH_TEST_BAKED_DSH:-1}" = 1 ]; then
  mkdir -p "$DSH_ROOTFS_DIR/opt/dsh/current/bin"
  printf '#!/bin/sh\n' >"$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
  chmod 0755 "$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
fi
exit 0
EOF
  chmod 0755 "$_bin/mount"
  cat >"$_bin/mountpoint" <<'EOF'
#!/bin/sh
exit 1
EOF
  chmod 0755 "$_bin/mountpoint"
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
if [ "${DSH_TEST_BAKED_DSH:-1}" = 1 ]; then
  mkdir -p "$DSH_ROOTFS_DIR/opt/dsh/current/bin"
  printf '#!/bin/sh\n' >"$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
  chmod 0755 "$DSH_ROOTFS_DIR/opt/dsh/current/bin/dsh"
fi
last=
for arg in "$@"; do last=$arg; done
exec "${DSH_TEST_SHELL:-/bin/sh}" "$last"
EOF
  cat >"$_bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  --help) printf '%s\n' '--sector-size --direct-io' ;;
esac
exit 0
EOF
  cat >"$_bin/debugfs" <<'EOF'
#!/bin/sh
case "${2:-}" in
  'stat /usr/bin/python3' | 'stat /opt/dsh/current/bin/dsh') printf 'Mode: 0755 Type: regular\n' ;;
  'stat /tmp') printf 'Mode: 1777 Type: directory\n' ;;
  *) exit 1 ;;
esac
EOF
  cat >"$_bin/stat" <<'EOF'
#!/bin/sh
if [ "${1:-}" = -c ] && [ "${2:-}" = %a ]; then
  if [ -d "$3" ]; then printf '700\n'; else printf '600\n'; fi
  exit 0
fi
if [ "${1:-}" = -c ] && [ "${2:-}" = %s ] && [ -n "${DSH_TEST_IMAGE_SIZE:-}" ]; then
  printf '%s\n' "$DSH_TEST_IMAGE_SIZE"
  exit 0
fi
exec /usr/bin/stat "$@"
EOF
  cat >"$_bin/readlink" <<'EOF'
#!/bin/sh
if /usr/bin/readlink "$@" 2>/dev/null; then exit 0; fi
case ${1:-} in
*/ns/mnt) printf 'mnt:[4026531840]\n'; exit 0 ;;
esac
exit 1
EOF
  chmod 0755 "$_bin/nsenter" "$_bin/unshare" "$_bin/losetup" "$_bin/stat" "$_bin/readlink" "$_bin/debugfs"
}

fixture_module() {
  _dst=$1
  cp -a "$MODULE" "$_dst"
  rm -rf "$_dst/tools/lib" "$_dst/tools/libexec" "$_dst/tools/licenses"
  rm -f "$_dst/tools/TERMUX-SOURCES.json" "$_dst/tools/bin/debugfs"
  mkdir -p "$_dst/tools/bin"
  cat >"$_dst/tools/bin/cp" <<'EOF'
#!/bin/sh
exec /usr/bin/cp "$@"
EOF
  cat >"$_dst/tools/bin/dsh-mount-ro-recursive" <<'EOF'
#!/bin/sh
if [ -n "${DSH_TEST_ROOT:-}" ]; then
  printf 'helper %s\n' "$*" >>"$DSH_TEST_ROOT/helper.log" 2>/dev/null || true
fi
if [ "${1:-}" = --claim ] || [ "${1:-}" = --claim-wait ]; then
  _claim_wait=0
  if [ "$1" = --claim-wait ]; then
    _claim_wait=$2
    case $_claim_wait in
    '' | *[!0-9]*) exit 2 ;;
    esac
    shift 2
  else
    shift
  fi
  _claim_guard=$1
  case $_claim_guard in
  "$DSH_ROOT"/*) ;;
  *) exit 1 ;;
  esac
  _claim_i=0
  until mkdir "$_claim_guard" 2>/dev/null; do
    [ "$_claim_i" -lt "$_claim_wait" ] || exit 1
    sleep 1 || exit 1
    _claim_i=$((_claim_i + 1))
  done
  shift
  "$@"
  _claim_rc=$?
  rm -rf "$_claim_guard"
  exit "$_claim_rc"
fi
if [ "${1:-}" = --rbind ] || [ "${1:-}" = --bind ]; then
  if [ "${1:-}" = --bind ] && [ "$3" = "$DSH_ROOTFS_DIR/root/.dsh" ] && [ -d "$2/workspace" ]; then
    mkdir -p "$3/workspace"
  fi
  if [ "${DSH_TEST_SKIP_MOUNTINFO:-0}" != 1 ] && [ -n "${DSH_MOUNTINFO:-}" ] && [ -f "$DSH_MOUNTINFO" ]; then
    encoded=$(printf '%s' "$3" | awk '{ gsub(/ /, "\\\\040"); print }')
    printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded" >>"$DSH_MOUNTINFO"
  fi
  if [ "${1:-}" = --rbind ] && [ "${3%/}" = "$DSH_ROOTFS_DIR/mnt" ]; then
    for nested in vendor/persist vendor/qmcs; do
      encoded=$(printf '%s' "$3/$nested" | awk '{ gsub(/ /, "\\\\040"); print }')
      printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded" >>"$DSH_MOUNTINFO"
    done
  fi
fi
if [ "${1:-}" = --rbind ] && [ "${3%/}" = "$DSH_ROOTFS_DIR/dev" ]; then
  if [ "${DSH_TEST_BLOCK_LINK:-0}" = 1 ]; then
    rm -rf "$DSH_ROOTFS_DIR/dev/block"
    ln -s /dev "$DSH_ROOTFS_DIR/dev/block"
  else
    mkdir -p "$DSH_ROOTFS_DIR/dev/block"
  fi
fi
exit 0
EOF
  chmod 0755 "$_dst/tools/bin/dsh-mount-ro-recursive" "$_dst/tools/bin/cp"
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
  export DSH_HOST_ROOT="$CASE/host"
  export DSH_TEST_ALLOW_NONROOT=1
  export DSH_TEST_ALLOW_UNUSUAL_PATHS=1
  export PATH="$CASE/bin:$PATH"

  mkdir -p "$DSH_ROOT/control" "$DSH_ROOT/state/ctl" "$DSH_ROOT/state/guest" "$DSH_ROOT/state/runtime" \
    "$DSH_ROOT/state/dsh-home" "$DSH_ROOT/state/workspace" "$DSH_ROOT/logs" "$DSH_ROOT/dsh" "$DSH_IMAGE_DIR" "$DSH_TMP" "$DSH_PROC/self/attr" \
    "$DSH_SYS/fs/selinux" "$DSH_ROOTFS_DIR" "$CASE/bin"
  cp -a "$DSH_MODULE_DIR/control/." "$DSH_ROOT/control/"
  for _path in data mnt data_mirror storage metadata system system_ext product vendor odm apex linkerconfig dev; do
    mkdir -p "$DSH_HOST_ROOT/$_path"
  done
  mkdir -p "$DSH_HOST_ROOT/data/media/0" "$DSH_HOST_ROOT/mnt/vendor/persist" "$DSH_HOST_ROOT/mnt/vendor/qmcs" \
    "$DSH_HOST_ROOT/dev/block"
  mkdir -p "$DSH_PROC/sys/kernel/random" "$DSH_PROC/1"
  printf '1 (dsh-test) S 0\n' >"$DSH_PROC/1/stat"
  printf '1 0 0:1 / / rw - ext4 /dev/root rw\n' >"$DSH_PROC/1/mountinfo"
  printf '00000000-0000-4000-8000-000000000001\n' >"$DSH_PROC/sys/kernel/random/uuid"
  printf '00000000-0000-4000-8000-000000000001\n' >"$DSH_PROC/sys/kernel/random/boot_id"
  export DSH_BOOT_ID_SOURCE="$DSH_PROC/sys/kernel/random/boot_id"
  chmod 0700 "$DSH_ROOT" "$DSH_IMAGE_DIR"
  printf '1\n' >"$DSH_SYS/fs/selinux/enforce"
  printf 'u:r:ksu:s0\n' >"$DSH_PROC/self/attr/current"
  printf '0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 hidepid=invisible\n' >"$DSH_PROC/self/mountinfo"
  printf 'x' >"$DSH_IMAGE"
  chmod 0600 "$DSH_IMAGE"
  : >"$DSH_IMAGE_DIR/.nomedia"
  export DSH_TEST_SHELL=${DSH_TEST_SHELL:-bash}
  fake_bin "$CASE/bin"
  export DSH_HOST_TRUE="$CASE/bin/true"
  cat >"$CASE/bin/dsh-ctl" <<EOF
#!/bin/sh
exec "$DSH_TEST_SHELL" "$DSH_MODULE_DIR/bin/dsh-ctl" "\$@"
EOF
  cat >"$CASE/bin/dsh-watchdog" <<EOF
#!/bin/sh
exec "$DSH_TEST_SHELL" "$DSH_MODULE_DIR/bin/dsh-watchdog" "\$@"
EOF
  chmod 0755 "$CASE/bin/dsh-ctl" "$CASE/bin/dsh-watchdog"
  export DSH_WATCHDOG="$CASE/bin/dsh-watchdog"
  DSH_CTL=$CASE/bin/dsh-ctl
}
