#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"

cat >"$CASE/bin/mountpoint" <<'EOF'
#!/bin/sh
exit 0
EOF
cat >"$CASE/bin/umount" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/umount-fail.log"
exit 1
EOF
chmod 0755 "$CASE/bin/umount"
export DSH_TEST_ROOT="$CASE"
export DSH_LOG_DIR="$CASE/logs"
export DSH_MOUNTINFO="$DSH_PROC/self/mountinfo"
mkdir -p "$DSH_LOG_DIR"
export DSH_MOUNT_STACK="$CASE/mounts"
printf '%s\n' "$DSH_ROOTFS_DIR" "$DSH_ROOTFS_DIR/proc" >"$DSH_MOUNT_STACK"
encoded_initial=${DSH_ROOTFS_DIR// /\\040}
cat >"$DSH_MOUNTINFO" <<EOF
1 0 0:1 / $encoded_initial rw - ext4 /dev/loop0 rw
2 1 0:2 / $encoded_initial/proc rw - proc proc rw
EOF
. "$MODULE/lib/common.sh"
. "$MODULE/lib/log.sh"
. "$MODULE/lib/mount.sh"
set +e
dsh_mount_reverse_stack
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "failed unmount returned success"
[ ! -e "$DSH_MOUNT_STACK.reverse.$$" ] || fail "temporary reverse list was retained"
! grep -q -- '-l' "$CASE/umount-fail.log" || fail "failed unmount silently became lazy"

tree_root="$CASE/tree root"
encoded_root=${tree_root// /\\040}
export DSH_ROOTFS_DIR="$tree_root"
export DSH_MOUNTINFO="$CASE/tree-mountinfo"
export DSH_MOUNT_STACK="$CASE/tree-mounts"
printf '%s\n' "$DSH_ROOTFS_DIR/dev" >"$DSH_MOUNT_STACK"
cat >"$DSH_MOUNTINFO" <<EOF
1 0 0:1 / $encoded_root/dev rw - tmpfs tmpfs rw
2 1 0:2 / $encoded_root/dev/pts rw - devpts devpts rw
3 2 0:3 / $encoded_root/dev/pts/inner rw - tmpfs tmpfs rw
4 3 0:4 / $encoded_root/dev/pts/inner/file rw - tmpfs tmpfs rw
5 0 0:5 / $encoded_root/outside rw - tmpfs tmpfs rw
EOF
cat >"$CASE/bin/mountpoint" <<'EOF'
#!/bin/sh
for target do :; done
case $target in
  */file) exit 1 ;;
esac
[ -f "$DSH_TEST_ROOT/tree-unmounted" ] || exit 0
if grep -Fqx -- "$target" "$DSH_TEST_ROOT/tree-unmounted"; then
  exit 1
fi
exit 0
EOF
cat >"$CASE/bin/umount" <<'EOF'
#!/bin/sh
target=$1
encoded=$(printf '%s' "$target" | awk '{ gsub(/ /, "\\\\040"); print }')
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/tree-umount.log"
if [ "$target" = "$DSH_ROOTFS_DIR/dev" ] && [ ! -e "$DSH_TEST_ROOT/tree-dev-failed" ]; then
  : >"$DSH_TEST_ROOT/tree-dev-failed"
  exit 1
fi
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/tree-unmounted"
awk -v target="$encoded" '$5 != target' "$DSH_MOUNTINFO" >"$DSH_MOUNTINFO.tmp"
mv "$DSH_MOUNTINFO.tmp" "$DSH_MOUNTINFO"
exit 0
EOF
chmod 0755 "$CASE/bin/mountpoint" "$CASE/bin/umount"
dsh_mount_reverse_stack
expected=$(printf '%s\n' "$tree_root/dev/pts/inner/file" "$tree_root/dev/pts/inner" "$tree_root/dev/pts" "$tree_root/outside" "$tree_root/dev" "$tree_root/dev")
actual=$(cat "$CASE/tree-umount.log")
assert_eq "$actual" "$expected"
[ ! -e "$DSH_MOUNT_STACK.tree.$$" ] || fail 'tree list was retained'
[ ! -e "$DSH_MOUNT_STACK.tree.$$.raw.$$" ] || fail 'raw tree list was retained'
[ ! -e "$DSH_MOUNT_STACK.tree.$$.sorted.$$" ] || fail 'sorted tree list was retained'
! grep -q -- '-l' "$CASE/tree-umount.log" || fail 'tree unmount silently became lazy'
: >"$DSH_MOUNT_STACK"
printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded_root" >>"$DSH_MOUNTINFO"
printf '1 0 0:1 / %s rw - tmpfs tmpfs rw\n' "$encoded_root/unrecorded" >>"$DSH_MOUNTINFO"
dsh_mount_reverse_stack
if dsh_mountinfo_has_tree "$tree_root"; then fail 'empty mount stack left a rootfs child mount'; fi

export DSH_MOUNT_STACK="$CASE/file-mounts"
export DSH_RECURSIVE_RO="$DSH_MODULE_DIR/tools/bin/dsh-mount-ro-recursive"
printf '{"version":"0.2.0"}\n' >"$CASE/module-meta.json"
dsh_mount_bind_ro_file "$CASE/module-meta.json" "$CASE/meta-target/module.json"
[ -f "$CASE/meta-target/module.json" ] || fail 'file bind target is not a regular file'
[ ! -d "$CASE/meta-target/module.json" ] || fail 'file bind target became a directory'
assert_eq "$(cat "$DSH_MOUNT_STACK")" "$CASE/meta-target/module.json"
grep -F -- "--remount-ro-bind $CASE/meta-target/module.json" "$CASE/helper.log" >/dev/null || fail 'file bind was not made read-only'
MSYS=winsymlinks:nativestrict ln -s "$CASE/module-meta.json" "$CASE/module-link.json"
[ -L "$CASE/module-link.json" ] || fail 'test did not create a real symlink'
if dsh_mount_bind_ro_file "$CASE/module-link.json" "$CASE/meta-target/link.json"; then fail 'symlink metadata source was accepted'; fi
if dsh_mount_bind_ro_file "$CASE/module-meta.json" "$CASE/meta-target"; then fail 'directory metadata target was accepted'; fi

printf 'test_mount_cleanup: ok\n'
