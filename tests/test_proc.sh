#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
export DSH_PROC="$case_dir/proc"
mkdir -p "$case_dir/bin"
cat >"$case_dir/bin/ls" <<'EOF'
#!/bin/sh
rc=0
emit_link() {
  [ -r "$1.target" ] || return 0
  printf 'lrwxrwxrwx 1 root root 0 Jan 1 00:00 %s -> %s\n' "$1" "$(cat "$1.target")"
}
for path do
  case $path in
    -*) continue ;;
    *'[0-9]*'*)
      suffix=${path##*'[0-9]*'}
      for pid in 101 103 104 105 10x 201 202 203 999; do
        entry=$DSH_PROC/$pid$suffix
        case $suffix in
          /ns/mnt | /root) emit_link "$entry" ;;
          *) /usr/bin/ls -ld "$entry" 2>/dev/null || rc=1 ;;
        esac
      done
      ;;
    */ns/mnt | */root) emit_link "$path" ;;
    *) /usr/bin/ls -ld "$path" 2>/dev/null || rc=1 ;;
  esac
done
exit "$rc"
EOF
chmod 0755 "$case_dir/bin/ls"
export PATH="$case_dir/bin:$PATH"
rootfs="$case_dir/root fs"
encoded_rootfs=${rootfs// /\\040}
for pid in 101 103 104 10x; do
  case $pid in
  104) ns='mnt:[40265355500]' ;;
  *) ns='mnt:[4026535550]' ;;
  esac
  mkdir -p "$DSH_PROC/$pid/ns"
  printf '%s\n' "$ns" >"$DSH_PROC/$pid/ns/mnt.target"
  printf '%s (worker) S 0\n' "$pid" >"$DSH_PROC/$pid/stat"
done
printf '%s\n' "$rootfs" >"$DSH_PROC/104/root.target"
. "$MODULE/lib/common.sh"
. "$MODULE/lib/proc.sh"

out=$(dsh_proc_scan_ns 'mnt:[4026535550]')
assert_eq "$out" "$(printf '101\n103')"
assert_eq "$(dsh_proc_scan_ns 'mnt:[40265355500]')" '104'
assert_eq "$(dsh_proc_scan_ns 'mnt:[404]')" ''
mkdir -p "$DSH_PROC/201" "$DSH_PROC/202" "$DSH_PROC/203"
printf '201 (worker) S 0\n' >"$DSH_PROC/201/stat"
printf '202 (worker) Z 0\n' >"$DSH_PROC/202/stat"
printf '203 (worker) name) S 0\n' >"$DSH_PROC/203/stat"
dsh_proc_alive 201 || fail 'running process was rejected'
if dsh_proc_alive 202; then fail 'zombie was treated as alive'; fi
dsh_proc_alive 203 || fail 'process name containing a parenthesis was misparsed'
if dsh_proc_alive 999; then fail 'missing process was treated as alive'; fi
printf '1 0 0:1 / %s rw - ext4 /dev/block/loop45 rw\n' "$encoded_rootfs" >"$DSH_PROC/101/mountinfo"
printf '1 0 0:1 / / rw - ext4 /dev/root rw\n' >"$DSH_PROC/103/mountinfo"
mkdir -p "$DSH_PROC/105/mountinfo"
printf '105 (kworker) S 0 0 0 0 0 2097152\n' >"$DSH_PROC/105/stat"
assert_eq "$(dsh_proc_scan_rootfs_ns 'mnt:[4026535550]' "$rootfs")" '101'
assert_eq "$(dsh_proc_scan_rootfs_ns 'mnt:[40265355500]' "$rootfs")" '104'
printf '1 0 0:1 / / rw - ext4 /dev/root rw\n' >"$DSH_PROC/104/mountinfo"
export DSH_TEST_ROOTFS="$rootfs"
cat >"$case_dir/bin/readlink" <<'EOF'
#!/bin/sh
case ${1:-} in
  */203/root) printf '/\n' ;;
  *) exec /usr/bin/readlink "$@" ;;
esac
EOF
chmod 0755 "$case_dir/bin/readlink"
if dsh_proc_ns_has_rootfs 203 "$rootfs"; then fail 'unrelated process root was accepted'; fi
rm -rf "$DSH_PROC/201" "$DSH_PROC/202" "$DSH_PROC/203"
for pid in 101 103 104; do printf '%s (worker) S 0\n' "$pid" >"$DSH_PROC/$pid/stat"; done
assert_eq "$(dsh_proc_any_rootfs "$rootfs")" "$(printf '101\n104')"
assert_eq "$(dsh_proc_any_rootfs "$rootfs" 101)" "104"
assert_eq "$(dsh_proc_any_rootfs "$rootfs" 104)" "101"
if dsh_proc_any_rootfs "$case_dir/not-mounted"; then fail 'unrelated mount was accepted'; fi
mkdir -p "$DSH_PROC/999/mountinfo"
printf '999 (worker) Z 0\n' >"$DSH_PROC/999/stat"
assert_eq "$(dsh_proc_any_rootfs "$rootfs")" "$(printf '101\n104')"
printf '999 (worker) S 0\n' >"$DSH_PROC/999/stat"
set +e
dsh_proc_any_rootfs "$rootfs" >/dev/null
rc=$?
set -e
assert_eq "$rc" 2
rm -rf "$DSH_PROC/999"
set +e
dsh_proc_scan_ns ''
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'empty namespace was accepted'
printf 'test_proc: ok\n'
