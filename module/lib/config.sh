# shellcheck shell=sh disable=SC3043,SC2034
# Configuration and derived paths. All values can be overridden by env for tests.

dsh_config_load_file() {
  _cfg=$1
  [ -f "$_cfg" ] || return 0

  while IFS= read -r _line || [ -n "${_line:-}" ]; do
    case $_line in
    '' | '#'*) continue ;;
    esac
    case $_line in
    *=*) ;;
    *) continue ;;
    esac
    _key=${_line%%=*}
    _val=${_line#*=}
    _key=$(dsh_trim "$_key")
    _val=$(dsh_trim "$_val")
    case $_val in
    \"*\")
      _val=${_val#\"}
      _val=${_val%\"}
      ;;
    \'*\')
      _val=${_val#\'}
      _val=${_val%\'}
      ;;
    esac
    case $_key in
    DSH_IMAGE_DIR) [ "${DSH_IMAGE_DIR+x}" = x ] || DSH_IMAGE_DIR=$_val ;;
    DSH_IMAGE) [ "${DSH_IMAGE+x}" = x ] || DSH_IMAGE=$_val ;;
    DSH_ROOTFS_DIR) [ "${DSH_ROOTFS_DIR+x}" = x ] || DSH_ROOTFS_DIR=$_val ;;
    DSH_MEDIA) [ "${DSH_MEDIA+x}" = x ] || DSH_MEDIA=$_val ;;
    DSH_TMP) [ "${DSH_TMP+x}" = x ] || DSH_TMP=$_val ;;
    DSH_GUEST_TMP) [ "${DSH_GUEST_TMP+x}" = x ] || DSH_GUEST_TMP=$_val ;;
    DSH_WORKSPACE_DIR) [ "${DSH_WORKSPACE_DIR+x}" = x ] || DSH_WORKSPACE_DIR=$_val ;;
    DSH_PROC) [ "${DSH_PROC+x}" = x ] || DSH_PROC=$_val ;;
    DSH_SYS) [ "${DSH_SYS+x}" = x ] || DSH_SYS=$_val ;;
    DSH_IMAGE_SIZE_GB) [ "${DSH_IMAGE_SIZE_GB+x}" = x ] || DSH_IMAGE_SIZE_GB=$_val ;;
    DSH_PROTECT_CRITICAL) [ "${DSH_PROTECT_CRITICAL+x}" = x ] || DSH_PROTECT_CRITICAL=$_val ;;
    DSH_SELINUXFS_MASK) [ "${DSH_SELINUXFS_MASK+x}" = x ] || DSH_SELINUXFS_MASK=$_val ;;
    DSH_SELINUXFS_MODE) [ "${DSH_SELINUXFS_MODE+x}" = x ] || DSH_SELINUXFS_MODE=$_val ;;
    DSH_BLOCK_ACCESS) [ "${DSH_BLOCK_ACCESS+x}" = x ] || DSH_BLOCK_ACCESS=$_val ;;
    DSH_SYSTEM_RW) [ "${DSH_SYSTEM_RW+x}" = x ] || DSH_SYSTEM_RW=$_val ;;
    *) dsh_warn "ignoring unknown config key: $_key" ;;
    esac
  done <"$_cfg"
}

dsh_config_init() {
  DSH_ROOT=${DSH_ROOT:-/data/adb/chroot}
  DSH_CONFIG=${DSH_CONFIG:-$DSH_ROOT/config.conf}

  if [ -r "$DSH_CONFIG" ]; then
    dsh_config_load_file "$DSH_CONFIG"
  fi

  : "${DSH_MEDIA:=/data/media/0}"
  : "${DSH_IMAGE_DIR:=$DSH_MEDIA/ubuntu-rootfs}"
  : "${DSH_IMAGE:=$DSH_IMAGE_DIR/rootfs.img}"
  : "${DSH_ROOTFS_DIR:=/tmp/dsh-rootfs}"
  : "${DSH_TMP:=/tmp}"
  : "${DSH_GUEST_TMP:=/var/tmp}"
  : "${DSH_PROC:=/proc}"
  : "${DSH_MOUNTINFO:=$DSH_PROC/self/mountinfo}"
  : "${DSH_SYS:=/sys}"
  : "${DSH_IMAGE_SIZE_GB:=128}"
  : "${DSH_PROTECT_CRITICAL:=1}"
  : "${DSH_SELINUXFS_MASK:=0}"
  if [ "${DSH_SELINUXFS_MODE+x}" != x ]; then
    if dsh_bool "$DSH_SELINUXFS_MASK"; then
      DSH_SELINUXFS_MODE=mask
    else
      DSH_SELINUXFS_MODE=ro
    fi
  fi
  : "${DSH_BLOCK_ACCESS:=ro}"
  : "${DSH_SYSTEM_RW:=0}"
  : "${DSH_APPLY:=0}"
  : "${DSH_HOST_ROOT:=}"

  DSH_CONTROL_DIR=$DSH_ROOT/control
  DSH_STATE_DIR=$DSH_ROOT/state
  DSH_CTL_DIR=$DSH_STATE_DIR/ctl
  DSH_GUEST_DIR=$DSH_STATE_DIR/guest
  DSH_GUEST_RUNTIME_DIR=$DSH_STATE_DIR/runtime
  DSH_DSH_HOME_DIR=$DSH_STATE_DIR/dsh-home
  DSH_WORKSPACE_HOST_DIR=$DSH_STATE_DIR/workspace
  : "${DSH_WORKSPACE_DIR:=/root/workspace}"
  DSH_LOG_DIR=$DSH_ROOT/logs
  DSH_RUNTIME_DIR=$DSH_ROOT/dsh
  DSH_SNAPSHOT_DIR=$DSH_ROOT/snapshots
  DSH_PAYLOAD_DIR=$DSH_ROOT/payload
  DSH_IMAGE_SNAPSHOT_DIR=$DSH_IMAGE_DIR/snapshots
  DSH_ROOTFS_STAGING=$DSH_ROOT/rootfs-staging

  DSH_LOCK_DIR=$DSH_CTL_DIR/lock
  DSH_IMAGE_CLAIM_FILE=$DSH_CTL_DIR/image-operation.lock
  DSH_PURGE_MARKER=$DSH_CTL_DIR/purge.in-progress
  DSH_WATCHDOG_DISABLED=$DSH_CTL_DIR/watchdog.disabled
  DSH_STATUS_FILE=$DSH_CTL_DIR/status
  DSH_LAST_GOOD_FILE=$DSH_CTL_DIR/last-good
  DSH_PID_FILE=$DSH_GUEST_DIR/launcher.pid
  DSH_STARTTIME_FILE=$DSH_GUEST_DIR/launcher.starttime
  DSH_BOOT_ID_FILE=$DSH_GUEST_DIR/launcher.bootid
  DSH_NS_FILE=$DSH_GUEST_DIR/launcher.ns
  DSH_LOOP_FILE=$DSH_GUEST_DIR/loopdev
  DSH_URL_FILE=$DSH_GUEST_RUNTIME_DIR/url
  DSH_HEALTH_FILE=$DSH_GUEST_RUNTIME_DIR/health
  DSH_MOUNTS_FILE=$DSH_GUEST_DIR/mounts
  DSH_CLEANUP_FILE=$DSH_GUEST_DIR/cleanup.status
  DSH_GUARD_FILE=$DSH_GUEST_RUNTIME_DIR/guard
  DSH_TTYD_PASS_FILE=$DSH_GUEST_RUNTIME_DIR/ttyd.pass
  DSH_TTYD_DOWN_FILE=$DSH_CONTROL_DIR/s6/ttyd/down

  DSH_NSINIT=$DSH_MODULE_DIR/bin/dsh-nsinit
  DSH_WATCHDOG=${DSH_WATCHDOG:-$DSH_MODULE_DIR/bin/dsh-watchdog}
  DSH_RECURSIVE_RO=$DSH_MODULE_DIR/tools/bin/dsh-mount-ro-recursive
  DSH_RECOVERY_DIR=$DSH_TMP/dsh-recovery
  DSH_RECOVERY_MARKER=$DSH_RECOVERY_DIR/owner
  DSH_RECOVERY_SCRIPT=$DSH_RECOVERY_DIR/recover-mounts
  DSH_RECOVERY_UNMOUNT=$DSH_RECOVERY_DIR/unmount.sh
  DSH_RECOVERY_MOUNTS=$DSH_RECOVERY_DIR/mounts
  DSH_RECOVERY_STATUS=$DSH_RECOVERY_DIR/status
}

dsh_config_check_paths() {
  _rc=0
  for _p in "$DSH_ROOT" "$DSH_IMAGE_DIR" "$DSH_IMAGE" "$DSH_ROOTFS_DIR" "$DSH_TMP" "$DSH_GUEST_TMP" "$DSH_WORKSPACE_DIR"; do
    if ! dsh_is_abs "$_p"; then
      dsh_err "path must be absolute: $_p"
      _rc=1
    fi
    case $_p in
    / | *..* | *'
'*)
      dsh_err "unsafe path: $_p"
      _rc=1
      ;;
    esac
  done
  case $DSH_WORKSPACE_DIR in
  /root | /root/.dsh | /root/.dsh/*)
    dsh_err "workspace must be outside DSH home: $DSH_WORKSPACE_DIR"
    _rc=1
    ;;
  esac
  if [ "$DSH_IMAGE_DIR" = "$DSH_MEDIA" ] || [ "$DSH_IMAGE_DIR" = "$DSH_ROOT" ]; then
    dsh_err "image directory must not equal the media or data root"
    _rc=1
  fi
  case $DSH_IMAGE in
  "$DSH_IMAGE_DIR"/*) ;;
  *)
    dsh_err "image must live under DSH_IMAGE_DIR: $DSH_IMAGE"
    _rc=1
    ;;
  esac
  if [ -L "$DSH_ROOTFS_DIR" ]; then
    dsh_err "rootfs mountpoint must not be a symlink: $DSH_ROOTFS_DIR"
    _rc=1
  fi
  case $DSH_SELINUXFS_MODE in
  mask | ro | rw) ;;
  *)
    dsh_err "invalid SELinuxFS mode: $DSH_SELINUXFS_MODE"
    _rc=1
    ;;
  esac
  case $DSH_BLOCK_ACCESS in
  masked | ro | full) ;;
  *)
    dsh_err "invalid block access mode: $DSH_BLOCK_ACCESS"
    _rc=1
    ;;
  esac
  return $_rc
}

dsh_config_dump() {
  cat <<EOF
DSH_ROOT=$DSH_ROOT
DSH_IMAGE=$DSH_IMAGE
DSH_IMAGE_DIR=$DSH_IMAGE_DIR
DSH_ROOTFS_DIR=$DSH_ROOTFS_DIR
DSH_TMP=$DSH_TMP
DSH_GUEST_TMP=$DSH_GUEST_TMP
DSH_WORKSPACE_DIR=$DSH_WORKSPACE_DIR
DSH_WORKSPACE_HOST_DIR=$DSH_WORKSPACE_HOST_DIR
DSH_IMAGE_SIZE_GB=$DSH_IMAGE_SIZE_GB
DSH_PROTECT_CRITICAL=$DSH_PROTECT_CRITICAL
DSH_SELINUXFS_MASK=$DSH_SELINUXFS_MASK
DSH_SELINUXFS_MODE=$DSH_SELINUXFS_MODE
DSH_BLOCK_ACCESS=$DSH_BLOCK_ACCESS
DSH_SYSTEM_RW=$DSH_SYSTEM_RW
DSH_APPLY=$DSH_APPLY
DSH_CONTROL_DIR=$DSH_CONTROL_DIR
DSH_STATE_DIR=$DSH_STATE_DIR
DSH_LOG_DIR=$DSH_LOG_DIR
DSH_RUNTIME_DIR=$DSH_RUNTIME_DIR
EOF
}
