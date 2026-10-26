# shellcheck shell=sh disable=SC3043
# Mount helpers used by the namespace holder. They are never called by
# doctor/plan/status and must only run inside the private mount namespace.

DSH_MOUNT_STACK=${DSH_MOUNT_STACK:-}

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
  dsh_mount_push "$_dst"
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
  if [ -x "${DSH_RECURSIVE_RO:-}" ]; then
    "$DSH_RECURSIVE_RO" --bind "$_src" "$_dst"
  elif [ -n "$_opts" ]; then
    mount -o "bind,$_opts" "$_src" "$_dst"
  else
    mount --bind "$_src" "$_dst" 2>/dev/null || mount -o bind "$_src" "$_dst"
  fi || {
    dsh_err "bind failed: $_src -> $_dst"
    return 1
  }
  dsh_mount_push "$_dst"
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
  mount --make-rprivate "$_dst" 2>/dev/null || true
  dsh_mount_push "$_dst"
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
  [ -s "$DSH_MOUNT_STACK" ] || return 0
  _rc=0
  # Read the stack into memory, then unmount newest-first.
  _targets=$(awk '{ a[NR] = $0 } END { for (i = NR; i >= 1; i--) print a[i] }' "$DSH_MOUNT_STACK")
  printf '%s\n' "$_targets" | while IFS= read -r _target; do
    [ -n "$_target" ] || continue
    if umount "$_target" 2>/dev/null; then
      dsh_log "umount: $_target"
    elif umount -l "$_target" 2>/dev/null; then
      dsh_warn "lazy umount: $_target"
    else
      dsh_warn "umount failed: $_target"
      _rc=1
    fi
  done
  # The while loop is a subshell in POSIX sh; return status is advisory.
  return 0
}

dsh_mount_reset_stack() {
  : >"$DSH_MOUNT_STACK" || return 1
}
