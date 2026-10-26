# shellcheck shell=sh disable=SC3043,SC2012
# Cold image/state snapshots and purge. Never snapshot a running holder.

dsh_cp_sparse() {
  _src=$1
  _dst=$2
  if cp --sparse=always "$_src" "$_dst" 2>/dev/null; then
    return 0
  fi
  cp "$_src" "$_dst"
}

dsh_sha256() {
  sha256sum "$1" 2>/dev/null | awk '{print $1}'
}

dsh_snapshot_require_stopped() {
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if dsh_is_uint "$_pid" && dsh_proc_alive "$_pid"; then
    dsh_err "holder is running (pid $_pid); stop before snapshot/restore"
    return 1
  fi
  return 0
}

dsh_snapshot_paths() {
  SNAP_NAME=$1
  SNAP_IMAGE=$DSH_IMAGE_SNAPSHOT_DIR/rootfs-$SNAP_NAME.img
  SNAP_STATE=$DSH_SNAPSHOT_DIR/state-$SNAP_NAME.tar.gz
  SNAP_MANIFEST=$DSH_SNAPSHOT_DIR/rootfs-$SNAP_NAME.manifest
}

dsh_snapshot_create() {
  _name=${1:-$(date '+%Y%m%d-%H%M%S' 2>/dev/null || date +%s)}
  case $_name in
  *[!A-Za-z0-9._-]* | '')
    dsh_err "invalid snapshot name: $_name"
    return 2
    ;;
  esac
  dsh_snapshot_require_stopped || return 1
  [ -f "$DSH_IMAGE" ] || {
    dsh_err "image missing: $DSH_IMAGE"
    return 1
  }
  mkdir -p "$DSH_IMAGE_SNAPSHOT_DIR" "$DSH_SNAPSHOT_DIR" "$DSH_CTL_DIR" || return 1
  dsh_snapshot_paths "$_name"
  [ ! -e "$SNAP_IMAGE" ] || {
    dsh_err "snapshot exists: $SNAP_IMAGE"
    return 1
  }
  _tmp=$SNAP_IMAGE.tmp.$$
  dsh_cp_sparse "$DSH_IMAGE" "$_tmp" || {
    rm -f "$_tmp"
    dsh_err "image copy failed"
    return 1
  }
  sync 2>/dev/null || true
  _sha=$(dsh_sha256 "$_tmp") || {
    rm -f "$_tmp"
    dsh_err "sha256 failed"
    return 1
  }
  mv -f "$_tmp" "$SNAP_IMAGE" || return 1

  tar -czf "$SNAP_STATE.tmp" -C "$DSH_ROOT" state 2>/dev/null || {
    rm -f "$SNAP_STATE.tmp"
    dsh_warn "state snapshot failed; image snapshot kept"
  }
  if [ -f "$SNAP_STATE.tmp" ]; then
    mv -f "$SNAP_STATE.tmp" "$SNAP_STATE" || true
  fi

  {
    printf 'name=%s\n' "$_name"
    printf 'created=%s\n' "$(dsh_timestamp)"
    printf 'image=%s\n' "$SNAP_IMAGE"
    printf 'sha256=%s\n' "$_sha"
    printf 'state=%s\n' "$([ -f "$SNAP_STATE" ] && printf '%s' "$SNAP_STATE" || printf '')"
  } >"$SNAP_MANIFEST.tmp" && mv -f "$SNAP_MANIFEST.tmp" "$SNAP_MANIFEST"
  printf 'snapshot: %s\nsha256: %s\n' "$SNAP_IMAGE" "$_sha"

  # Retain at most 7 image snapshots; sorted oldest first.
  _list=$(ls -1t "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img 2>/dev/null | tail -n +8)
  if [ -n "$_list" ]; then
    printf '%s\n' "$_list" | while IFS= read -r _old; do
      [ -n "$_old" ] || continue
      case $_old in "$DSH_IMAGE_SNAPSHOT_DIR"/*) rm -f "$_old" ;; esac
      _base=${_old##*/}
      _stem=${_base#rootfs-}
      _stem=${_stem%.img}
      rm -f "$DSH_SNAPSHOT_DIR/state-$_stem.tar.gz" "$DSH_SNAPSHOT_DIR/rootfs-$_stem.manifest" 2>/dev/null || true
    done
  fi
  return 0
}

dsh_snapshot_list() {
  printf 'image snapshots: %s\n' "$DSH_IMAGE_SNAPSHOT_DIR"
  if [ -d "$DSH_IMAGE_SNAPSHOT_DIR" ]; then
    ls -1t "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img 2>/dev/null || true
  fi
  printf '\nstate snapshots: %s\n' "$DSH_SNAPSHOT_DIR"
  if [ -d "$DSH_SNAPSHOT_DIR" ]; then
    ls -1t "$DSH_SNAPSHOT_DIR"/state-*.tar.gz 2>/dev/null || true
  fi
}

dsh_snapshot_restore() {
  _name=${1:-}
  [ -n "$_name" ] || {
    dsh_err "restore requires a snapshot name (use snapshot list)"
    return 2
  }
  dsh_snapshot_require_stopped || return 1
  dsh_snapshot_paths "$_name"
  [ -f "$SNAP_IMAGE" ] || {
    dsh_err "snapshot image missing: $SNAP_IMAGE"
    return 1
  }
  _want=$(sed -n 's/^sha256=//p' "$SNAP_MANIFEST" 2>/dev/null | head -n 1)
  _got=$(dsh_sha256 "$SNAP_IMAGE" || true)
  if [ -n "$_want" ] && [ "$_want" != "$_got" ]; then
    dsh_err "snapshot sha256 mismatch: want $_want got $_got"
    return 1
  fi
  _bad=$DSH_IMAGE.bad.$(date +%s 2>/dev/null || echo now)
  [ -e "$DSH_IMAGE" ] && mv -f "$DSH_IMAGE" "$_bad" || true
  _tmp=$DSH_IMAGE.tmp.$$
  dsh_cp_sparse "$SNAP_IMAGE" "$_tmp" || {
    rm -f "$_tmp"
    dsh_err "restore copy failed"
    return 1
  }
  mv -f "$_tmp" "$DSH_IMAGE" || return 1
  if [ -f "$SNAP_STATE" ]; then
    tar -xzf "$SNAP_STATE" -C "$DSH_ROOT" 2>/dev/null || dsh_warn "state restore failed"
  fi
  dsh_state_set state=stopped "note=restored:${_name}" "restored=$(dsh_timestamp)"
  printf 'restored image: %s\nprevious image kept: %s\n' "$DSH_IMAGE" "${_bad:-none}"
  return 0
}

dsh_purge_guard() {
  if [ "${DSH_TEST_ALLOW_UNUSUAL_PATHS:-0}" = 1 ]; then
    return 0
  fi
  case $DSH_ROOT in
  /data/adb/chroot) ;;
  *)
    dsh_err "refusing purge outside /data/adb/chroot: $DSH_ROOT"
    return 1
    ;;
  esac
  case $DSH_IMAGE_DIR in
  /data/media/0/ubuntu-rootfs) ;;
  *)
    dsh_err "refusing purge outside /data/media/0/ubuntu-rootfs: $DSH_IMAGE_DIR"
    return 1
    ;;
  esac
}

dsh_purge() {
  _yes=0
  _keep=0
  for _arg in "$@"; do
    case $_arg in
    --yes) _yes=1 ;;
    --keep-image) _keep=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl purge --yes [--keep-image]\n'
      return 0
      ;;
    *)
      dsh_err "unknown purge option: $_arg"
      return 2
      ;;
    esac
  done
  [ "$_yes" -eq 1 ] || {
    dsh_err "purge requires --yes"
    return 2
  }
  dsh_purge_guard || return 1
  dsh_stop || true
  _img_loop=$(dsh_loop_attached_image "$DSH_IMAGE" 2>/dev/null || true)
  [ -z "$_img_loop" ] || {
    dsh_err "image still attached: $_img_loop; refusing purge"
    return 1
  }
  rm -rf "$DSH_ROOT" || {
    dsh_err "cannot remove $DSH_ROOT"
    return 1
  }
  if [ "$_keep" -eq 0 ]; then
    rm -rf "$DSH_IMAGE_DIR" || {
      dsh_err "cannot remove $DSH_IMAGE_DIR"
      return 1
    }
  fi
  printf 'purged: %s%s\n' "$DSH_ROOT" "$([ "$_keep" -eq 1 ] && printf ' (image kept)' || printf ' + image dir')"
}
