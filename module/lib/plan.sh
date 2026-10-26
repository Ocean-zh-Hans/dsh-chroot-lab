# shellcheck shell=sh disable=SC3043
# Dry-run plan only. This file must never execute a mount/loop/unshare/chroot.

dsh_plan_run() {
  _check=0
  for _arg in "$@"; do
    case $_arg in
    --check) _check=1 ;;
    -h | --help)
      dsh_plan_help
      return 0
      ;;
    *)
      dsh_err "unknown plan option: $_arg"
      return 2
      ;;
    esac
  done

  dsh_config_check_paths || return 2

  if [ "$_check" -eq 1 ]; then
    dsh_doctor_run --for-apply
    _rc=$?
    if [ "$_rc" -ne 0 ]; then
      printf '\nPlan blocked: apply-readiness checks failed (rc=%d).\n' "$_rc"
      return "$_rc"
    fi
  fi

  printf 'DSH Ubuntu Chroot plan %s\n' "$DSH_VERSION"
  printf 'Mode: DRY RUN ONLY — no commands are executed\n\n'

  printf '== Decisions ==\n'
  printf '1A full /data + best-effort internal masks\n'
  printf '2B /sys rw + private selinuxfs tmpfs mask\n'
  printf '3A full /dev rbind\n'
  printf '4B host thin agent (optional)\n'
  printf '5A full chroot\n'
  printf '6B critical paths recursive ro\n'
  printf '7A system-rw off\n'
  printf '8A %sGB image\n' "$DSH_IMAGE_SIZE_GB"
  printf '9A image + state + /sdcard/DSH snapshots\n'
  printf '10A spare device/VM first\n'
  printf '11A approval-free + hardguard\n'
  printf '12A boot + last-good rollback\n'
  printf '13A accept u:r:ksu permissive\n'
  printf '14B recursive ro /apex + /linkerconfig\n'
  printf '15A full /storage rbind\n\n'

  printf '== Paths ==\n'
  printf 'module dir      : %s\n' "$DSH_MODULE_DIR"
  printf 'data root       : %s\n' "$DSH_ROOT"
  printf 'rootfs image    : %s\n' "$DSH_IMAGE"
  printf 'image dir       : %s\n' "$DSH_IMAGE_DIR"
  printf 'mountpoint      : %s\n' "$DSH_ROOTFS_DIR"
  printf 'control         : %s\n' "$DSH_CONTROL_DIR"
  printf 'state           : %s\n' "$DSH_STATE_DIR"
  printf 'logs            : %s\n' "$DSH_LOG_DIR"
  printf 'DSH runtime     : %s\n' "$DSH_RUNTIME_DIR"
  printf 'DSH home        : %s\n' "$DSH_DSH_HOME_DIR"
  printf 'DSH workspace   : %s/DSH/workspace\n\n' "$DSH_MEDIA"

  printf '== Image ==\n'
  if [ -e "$DSH_IMAGE" ]; then
    _size=$(dsh_file_size "$DSH_IMAGE" || true)
    printf 'present        : yes\n'
    printf 'apparent size  : %s bytes\n' "${_size:-unknown}"
    printf 'private mode   : '
    if dsh_mode_private "$DSH_IMAGE"; then printf 'yes\n'; else printf 'NO (%s)\n' "$(dsh_file_mode "$DSH_IMAGE" || true)"; fi
  else
    printf 'present        : no (P2/P3 must create it)\n'
  fi
  printf '\n'

  printf '== Start sequence (design only) ==\n'
  cat <<'EOF'
1. mkdir atomic lock; doctor --for-apply; mkdir -p rootfs mountpoint
2. e2fsck -p image; verify no second loop for the same backing file
3. util-linux losetup --sector-size 4096 --direct-io=off
4. setsid unshare -m -u dsh-nsinit; dsh-nsinit makes / rprivate first
5. dsh-nsinit: host mounts -> chroot -> fork/exec s6-svscan child; holder waits
6. record holder pid, PGID, starttime, /proc/PID/ns/mnt
7. wait for URL/ready; on failure keep holder for reverse cleanup
EOF
  printf '\n'

  printf '== Mount plan ==\n'
  cat <<'EOF'
/                     ext4 image at /tmp/dsh-rootfs
/proc                 proc
/sys                  sysfs rw
/sys/fs/selinux       private tmpfs mask (do NOT bind/remount host selinuxfs)
/dev                  rbind /dev
/dev/pts              devpts newinstance
/dev/shm              tmpfs 1g
/run                  tmpfs 1g
/tmp                  tmpfs 4g
/data                 rbind /data rw
/mnt                  rbind /mnt rw
/data_mirror          rbind /data_mirror rw
/storage              rbind /storage rw
/sdcard               bind /data/media/0 rw (raw, exec)
/system               rbind /system
/system_ext           rbind /system_ext
/product              rbind /product
/vendor               rbind /vendor
/odm                  rbind /odm
/apex                 rbind /apex + recursive ro
/linkerconfig         rbind /linkerconfig + recursive ro
/metadata             rbind + recursive ro
/mnt/vendor/persist   recursive ro
/mnt/vendor/qmcs      recursive ro
/sys/kernel/debug     debugfs rw
/sys/kernel/tracing   tracefs rw
/opt/dsh              bind runtime ro
/opt/dsh-control      bind control ro
/root/.dsh            bind dsh-home rw
/run/dsh              bind guest state rw
/var/log/dsh          bind logs rw
EOF
  printf '\n'

  printf '== 1A masks (best effort, after control binds) ==\n'
  cat <<'EOF'
/data/adb/chroot
/data/adb/modules
/data/adb/ksu
/data/media/0/ubuntu-rootfs
/sdcard/ubuntu-rootfs
/storage/emulated/0/ubuntu-rootfs
/mnt/user/0/emulated/0/ubuntu-rootfs
/mnt/installer/0/emulated/0/ubuntu-rootfs
/mnt/androidwritable/0/emulated/0/ubuntu-rootfs
/mnt/pass_through/0/emulated/0/ubuntu-rootfs
EOF
  printf 'Masks are integrity guards, not a security boundary; /proc/1/root remains a bypass.\n\n'

  printf '== Stop sequence (design only) ==\n'
  cat <<'EOF'
1. lock; validate pid/starttime/ns inode
2. open /proc/PID/ns/mnt fd; nsenter --mount=fd chroot /tmp/dsh-rootfs ...
3. s6-svc -d dsh/ttyd; s6-svwait -d; TERM holder only; KILL only if needed
4. holder cleanup: remount root ro; reverse umount; scan /proc/*/ns/mnt
5. kill holder; losetup -d; wait for real detach; restore SELinux; remove lock
EOF
  printf '\n'

  printf '== Safety gate ==\n'
  printf 'Apply is explicit (start --apply) and remains unverified until P2 spare-device/VM testing.\n'
  printf 'Do not run the mount sequence on the primary device until P2 is signed off.\n'
  return 0
}

dsh_plan_help() {
  cat <<'EOF'
Usage: dsh-ctl plan [--check]

Print the full mount/lifecycle plan. Never executes it.

  --check   run doctor --for-apply first; block the plan if it fails
EOF
}
