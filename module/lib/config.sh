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
    _is_set=''
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
    DSH_IMAGE_DIR | DSH_IMAGE | DSH_ROOTFS_DIR | DSH_MEDIA | DSH_TMP | DSH_PROC | DSH_SYS | DSH_IMAGE_SIZE_GB | DSH_PROTECT_CRITICAL | DSH_SELINUXFS_MASK | DSH_SYSTEM_RW | DSH_APPLY)
      eval "_is_set=\${$_key+x}"
      if [ "$_is_set" != x ]; then
        eval "$_key=\$_val"
      fi
      ;;
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
  : "${DSH_PROC:=/proc}"
  : "${DSH_SYS:=/sys}"
  : "${DSH_IMAGE_SIZE_GB:=128}"
  : "${DSH_PROTECT_CRITICAL:=1}"
  : "${DSH_SELINUXFS_MASK:=1}"
  : "${DSH_SYSTEM_RW:=0}"
  : "${DSH_APPLY:=0}"

  DSH_CONTROL_DIR=$DSH_ROOT/control
  DSH_STATE_DIR=$DSH_ROOT/state
  DSH_CTL_DIR=$DSH_STATE_DIR/ctl
  DSH_GUEST_DIR=$DSH_STATE_DIR/guest
  DSH_DSH_HOME_DIR=$DSH_STATE_DIR/dsh-home
  DSH_LOG_DIR=$DSH_ROOT/logs
  DSH_RUNTIME_DIR=$DSH_ROOT/dsh
  DSH_SNAPSHOT_DIR=$DSH_ROOT/snapshots
  DSH_PAYLOAD_DIR=$DSH_ROOT/payload
  DSH_IMAGE_SNAPSHOT_DIR=$DSH_IMAGE_DIR/snapshots
  DSH_ROOTFS_STAGING=$DSH_ROOT/rootfs-staging

  DSH_LOCK_DIR=$DSH_CTL_DIR/lock
  DSH_STATUS_FILE=$DSH_CTL_DIR/status
  DSH_LAST_GOOD_FILE=$DSH_CTL_DIR/last-good
  DSH_PID_FILE=$DSH_GUEST_DIR/launcher.pid
  DSH_STARTTIME_FILE=$DSH_GUEST_DIR/launcher.starttime
  DSH_NS_FILE=$DSH_GUEST_DIR/launcher.ns
  DSH_LOOP_FILE=$DSH_GUEST_DIR/loopdev
  DSH_URL_FILE=$DSH_GUEST_DIR/url

  DSH_NSINIT=$DSH_MODULE_DIR/bin/dsh-nsinit
  DSH_WATCHDOG=$DSH_MODULE_DIR/bin/dsh-watchdog
  DSH_RECURSIVE_RO=$DSH_MODULE_DIR/tools/bin/dsh-mount-ro-recursive
}

dsh_config_check_paths() {
  _rc=0
  for _p in "$DSH_ROOT" "$DSH_IMAGE_DIR" "$DSH_IMAGE" "$DSH_ROOTFS_DIR"; do
    if ! dsh_is_abs "$_p"; then
      dsh_err "path must be absolute: $_p"
      _rc=1
    fi
    case $_p in
    *..*)
      dsh_err "path must not contain '..': $_p"
      _rc=1
      ;;
    esac
  done
  return $_rc
}

dsh_config_dump() {
  cat <<EOF
DSH_ROOT=$DSH_ROOT
DSH_IMAGE=$DSH_IMAGE
DSH_IMAGE_DIR=$DSH_IMAGE_DIR
DSH_ROOTFS_DIR=$DSH_ROOTFS_DIR
DSH_IMAGE_SIZE_GB=$DSH_IMAGE_SIZE_GB
DSH_PROTECT_CRITICAL=$DSH_PROTECT_CRITICAL
DSH_SELINUXFS_MASK=$DSH_SELINUXFS_MASK
DSH_SYSTEM_RW=$DSH_SYSTEM_RW
DSH_APPLY=$DSH_APPLY
DSH_CONTROL_DIR=$DSH_CONTROL_DIR
DSH_STATE_DIR=$DSH_STATE_DIR
DSH_LOG_DIR=$DSH_LOG_DIR
DSH_RUNTIME_DIR=$DSH_RUNTIME_DIR
EOF
}
