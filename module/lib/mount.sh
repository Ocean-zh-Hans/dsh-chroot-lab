# shellcheck shell=sh disable=SC3043
# Mount helpers used by the namespace holder. They are never called by
# doctor/plan/status and must only run inside the private mount namespace.

DSH_MOUNT_STACK=${DSH_MOUNT_STACK:-}
# shellcheck disable=SC1090
. "$DSH_MODULE_DIR/lib/unmount.sh" || return 1

dsh_mount_log() {
  dsh_log "mount: $*"
}

dsh_mount_prepare_point() {
  _p=$1
  [ -d "$_p" ] || mkdir -p "$_p" || {
    dsh_err "cannot create mountpoint: $_p"
    return 1
  }
}

dsh_mount_push() {
  _target=$1
  [ -n "$DSH_MOUNT_STACK" ] || {
    dsh_err "DSH_MOUNT_STACK is not set"
    return 1
  }
  printf '%s\n' "$_target" >>"$DSH_MOUNT_STACK" || return 1
}

dsh_mount_one() {
  # Args: type source target [options...]
  _type=$1
  _src=$2
  _dst=$3
  shift 3
  dsh_mount_prepare_point "$_dst" || return 1
  dsh_mount_log "$_type $_src -> $_dst $*"
  if [ "$#" -gt 0 ]; then
    mount -t "$_type" -o "$*" "$_src" "$_dst"
  else
    mount -t "$_type" "$_src" "$_dst"
  fi || {
    dsh_err "mount failed: $_type $_src -> $_dst"
    return 1
  }
  if ! dsh_mount_push "$_dst"; then
    dsh_err "mount bookkeeping failed; unmounting immediately: $_dst"
    umount "$_dst" 2>/dev/null || dsh_warn "emergency umount failed: $_dst"
    return 1
  fi
}

dsh_mount_tmpfs() {
  _dst=$1
  _opts=${2:-size=64m,mode=0755}
  dsh_mount_one tmpfs tmpfs "$_dst" "$_opts"
}

dsh_mount_bind() {
  _src=$1
  _dst=$2
  _opts=${3:-}
  dsh_mount_prepare_point "$_dst" || return 1
  dsh_mount_log "bind $_src -> $_dst $_opts"
  if [ -x "${DSH_RECURSIVE_RO:-}" ] && [ -z "$_opts" ]; then
    "$DSH_RECURSIVE_RO" --bind "$_src" "$_dst"
  elif [ -n "$_opts" ]; then
    mount -o "bind,$_opts" "$_src" "$_dst"
  else
    mount --bind "$_src" "$_dst" 2>/dev/null || mount -o bind "$_src" "$_dst"
  fi || {
    dsh_err "bind failed: $_src -> $_dst"
    return 1
  }
  if ! dsh_mount_push "$_dst"; then
    dsh_err "mount bookkeeping failed; unmounting immediately: $_dst"
    umount "$_dst" 2>/dev/null || dsh_warn "emergency umount failed: $_dst"
    return 1
  fi
}

dsh_mount_bind_ro_file() {
  _src=$1
  _dst=$2
  dsh_is_abs "$_src" && dsh_is_abs "$_dst" || return 1
  [ -f "$_src" ] && [ ! -L "$_src" ] || {
    dsh_err "read-only bind source must be a regular file: $_src"
    return 1
  }
  if [ -L "$_dst" ] || { [ -e "$_dst" ] && [ ! -f "$_dst" ]; }; then
    dsh_err "read-only bind target must be a regular file: $_dst"
    return 1
  fi
  _parent=${_dst%/*}
  [ -d "$_parent" ] || mkdir -p "$_parent" || return 1
  if [ ! -e "$_dst" ]; then
    : >"$_dst" || return 1
  fi
  dsh_mount_log "bind-ro-file $_src -> $_dst"
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --bind "$_src" "$_dst"
  else
    mount --bind "$_src" "$_dst" 2>/dev/null || mount -o bind "$_src" "$_dst"
  fi || {
    dsh_err "file bind failed: $_src -> $_dst"
    return 1
  }
  if ! dsh_mount_push "$_dst"; then
    umount "$_dst" 2>/dev/null || dsh_warn "emergency file unmount failed: $_dst"
    return 1
  fi
  dsh_mount_remount_ro "$_dst"
}

dsh_mount_rbind() {
  _src=$1
  _dst=$2
  dsh_mount_prepare_point "$_dst" || return 1
  dsh_mount_log "rbind $_src -> $_dst"
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --rbind "$_src" "$_dst"
  else
    mount --rbind "$_src" "$_dst" 2>/dev/null || mount -o rbind "$_src" "$_dst"
  fi || {
    dsh_err "rbind failed: $_src -> $_dst"
    return 1
  }
  if ! dsh_mount_push "$_dst"; then
    dsh_err "mount bookkeeping failed; unmounting immediately: $_dst"
    dsh_unmount_tree "$_dst" "$DSH_MOUNT_STACK.tree.$$" "$DSH_ROOTFS_DIR" || dsh_warn "emergency umount failed: $_dst"
    return 1
  fi
  dsh_mount_make_rprivate "$_dst" || {
    dsh_err "cannot make mount tree private: $_dst"
    return 1
  }
}

dsh_mount_remount_ro() {
  _dst=$1
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --remount-ro-bind "$_dst"
    return $?
  fi
  mount -o remount,ro,bind "$_dst" 2>/dev/null || {
    dsh_warn "could not make read-only: $_dst"
    return 1
  }
}

dsh_mount_remount_ro_plain() {
  _dst=$1
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --remount-ro "$_dst"
    return $?
  fi
  mount -o remount,ro "$_dst" 2>/dev/null || {
    dsh_warn "could not remount read-only: $_dst"
    return 1
  }
}

dsh_mount_make_rprivate() {
  _path=${1:-/}
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --make-rprivate "$_path"
    return $?
  fi
  mount --make-rprivate "$_path"
}

dsh_mount_ro_recursive() {
  _dst=$1
  [ -x "$DSH_RECURSIVE_RO" ] || {
    dsh_err "recursive-ro helper missing: $DSH_RECURSIVE_RO"
    return 1
  }
  "$DSH_RECURSIVE_RO" "$_dst" || {
    dsh_err "recursive ro failed: $_dst"
    return 1
  }
}

dsh_mount_mask_tmpfs() {
  _dst=$1
  [ -e "$_dst" ] || {
    dsh_warn "mask target does not exist; skipped: $_dst"
    return 0
  }
  # Mask with a private tmpfs; never bind/remount selinuxfs.
  dsh_mount_tmpfs "$_dst" "size=1m,mode=000"
}

dsh_mount_reverse_stack() {
  _rc=0
  if [ -s "$DSH_MOUNT_STACK" ]; then
    while IFS= read -r _target; do
      [ -n "$_target" ] || continue
      case $_target in
      "$DSH_ROOTFS_DIR" | "$DSH_ROOTFS_DIR"/*) ;;
      *)
        dsh_warn "refusing unexpected mount target: $_target"
        _rc=1
        ;;
      esac
    done <"$DSH_MOUNT_STACK"
  fi
  dsh_unmount_tree "$DSH_ROOTFS_DIR" "$DSH_MOUNT_STACK.tree.$$" "$DSH_ROOTFS_DIR" || dsh_warn "first mount cleanup pass incomplete; rescanning"
  if dsh_mountinfo_has_tree "$DSH_ROOTFS_DIR"; then
    dsh_unmount_tree "$DSH_ROOTFS_DIR" "$DSH_MOUNT_STACK.tree.retry.$$" "$DSH_ROOTFS_DIR" || _rc=1
  fi
  if dsh_mountinfo_has_tree "$DSH_ROOTFS_DIR"; then
    dsh_warn "rootfs or a child mount remains after stack cleanup: $DSH_ROOTFS_DIR"
    _rc=1
  else
    _mount_rc=$?
    [ "$_mount_rc" -eq 1 ] || {
      dsh_warn "cannot verify the rootfs mount after cleanup"
      _rc=1
    }
  fi
  return "$_rc"
}

dsh_mount_reset_stack() {
  : >"$DSH_MOUNT_STACK" || return 1
}
