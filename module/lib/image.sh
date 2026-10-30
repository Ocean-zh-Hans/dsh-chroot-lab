# shellcheck shell=sh disable=SC3043,SC2012
# Image creation and verified installation. Never modify an active image.

dsh_image_backup_prune() {
  local _kind _list _old
  for _kind in install restore; do
    _list=$(ls -1t "$DSH_IMAGE_DIR"/rootfs-pre-"$_kind"-*.img 2>/dev/null | tail -n +6)
    [ -n "$_list" ] || continue
    printf '%s\n' "$_list" | while IFS= read -r _old; do
      case $_old in
      "$DSH_IMAGE_DIR"/rootfs-pre-"$_kind"-*.img) rm -f "$_old" ;;
      esac
    done
  done
}

dsh_image_publish() {
  local _tmp="$1" _replace="$2" _previous
  [ -f "$_tmp" ] || return 1
  chmod 0600 "$_tmp" || return 1
  if [ -e "$DSH_IMAGE" ]; then
    [ "$_replace" -eq 1 ] || {
      dsh_err "image already exists: $DSH_IMAGE"
      return 1
    }
    _previous=$DSH_IMAGE_DIR/rootfs-pre-install-$(date +%s)-$$.img
    if ! ln "$DSH_IMAGE" "$_previous" 2>/dev/null; then
      dsh_cp_sparse "$DSH_IMAGE" "$_previous" || return 1
      [ "$(dsh_sha256 "$_previous")" = "$(dsh_sha256 "$DSH_IMAGE")" ] || return 1
    fi
    dsh_log "previous image retained: $_previous"
  fi
  mv -f "$_tmp" "$DSH_IMAGE" || return 1
  dsh_image_backup_prune
}

dsh_image_create_locked() {
  local _source="$1" _size="$2" _tmp _truncate _mke2fs _e2fsck
  dsh_is_abs "$_source" || {
    dsh_err "source must be an absolute directory: $_source"
    return 2
  }
  case $_source in
  *..* | *'
'*)
    dsh_err "unsafe source directory: $_source"
    return 2
    ;;
  esac
  case $_size in
  [0-9]*[GgMmKk])
    _size_num=${_size%?}
    case $_size_num in
    '' | *[!0-9]*)
      dsh_err "invalid image size: $_size"
      return 2
      ;;
    esac
    ;;
  *)
    dsh_err "invalid image size: $_size"
    return 2
    ;;
  esac
  [ -d "$_source" ] || {
    dsh_err "staging rootfs missing: $_source"
    return 1
  }
  case $_source in
  / | /data | /data/adb | /data/media | /mnt | /storage | /sdcard | "$DSH_ROOT" | "$DSH_IMAGE_DIR")
    dsh_err "refusing obvious non-rootfs source: $_source"
    return 2
    ;;
  esac
  [ -d "$_source/etc" ] && [ -d "$_source/usr" ] || {
    dsh_err "source does not look like a rootfs (missing etc/usr): $_source"
    return 2
  }
  [ ! -e "$DSH_IMAGE" ] || {
    dsh_err "image already exists: $DSH_IMAGE"
    return 1
  }
  dsh_snapshot_require_cold || return 1
  mkdir -p "$DSH_IMAGE_DIR" || return 1
  chmod 0700 "$DSH_IMAGE_DIR" || return 1
  : >"$DSH_IMAGE_DIR/.nomedia" || return 1
  dsh_snapshot_require_space "$_source" "$DSH_STATE_DIR" || return 1
  _truncate=$(dsh_tool_path truncate || true)
  _mke2fs=$(dsh_tool_path mke2fs || true)
  _e2fsck=$(dsh_tool_path e2fsck || true)
  [ -n "$_truncate" ] && [ -n "$_mke2fs" ] && [ -n "$_e2fsck" ] || {
    dsh_err "truncate/mke2fs/e2fsck missing"
    return 1
  }
  _tmp=$DSH_IMAGE.tmp.$$
  [ ! -e "$_tmp" ] || {
    dsh_err "temporary image already exists: $_tmp"
    return 1
  }
  "$_truncate" -s "$_size" "$_tmp" || return 1
  "$_mke2fs" -t ext4 -b 4096 -d "$_source" "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  dsh_e2fsck_prepare "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  dsh_snapshot_fsck_clean "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  dsh_image_publish "$_tmp" 0 || {
    rm -f "$_tmp"
    return 1
  }
  printf 'created image: %s\nsize: %s\n' "$DSH_IMAGE" "$_size"
}

dsh_image_create() {
  local _apply=0 _source="$DSH_ROOTFS_STAGING" _size="${DSH_IMAGE_SIZE_GB}G" _arg _rc
  while [ "$#" -gt 0 ]; do
    _arg=$1
    case $_arg in
    --apply) _apply=1 ;;
    --source)
      [ "$#" -ge 2 ] || {
        dsh_err "--source requires a directory"
        return 2
      }
      _source=$2
      shift
      ;;
    --source=*) _source=${_arg#--source=} ;;
    --size)
      [ "$#" -ge 2 ] || {
        dsh_err "--size requires a value"
        return 2
      }
      _size=$2
      shift
      ;;
    --size=*) _size=${_arg#--size=} ;;
    -h | --help)
      printf 'Usage: dsh-ctl image create --source DIR [--size 128G] --apply\n'
      return 0
      ;;
    *)
      dsh_err "unknown image option: $_arg"
      return 2
      ;;
    esac
    shift
  done
  if [ "$_apply" -ne 1 ]; then
    _preview_size=$(du -sk "$_source" 2>/dev/null | awk '{print $1 * 1024}')
    [ -n "$_preview_size" ] || _preview_size=unknown
    printf 'image: would create %s from %s\nsource-size: %s bytes\n(use --apply)\n' "$DSH_IMAGE" "$_source" "$_preview_size"
    return 3
  fi
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" = 1 ]; then
    dsh_image_create_claimed "$_source" "$_size"
  else
    dsh_image_claim_exec image-create "$_source" "$_size"
  fi
}

dsh_image_expected_hash() {
  local _file="$1" _base="$2"
  awk -v base="$_base" '{
    file=$2
    sub(/^\*/, "", file)
    sub(/^\.\//, "", file)
    count=split(file, parts, "/")
    if (parts[count] == base) {
      print $1
      exit
    }
  }' "$_file" | tr 'A-F' 'a-f'
}

dsh_image_install_locked() {
  local _source="$1" _checksum="$2" _replace="$3" _expected _actual _tmp
  if ! dsh_is_abs "$_source" || [ ! -f "$_source" ]; then
    dsh_err "image source must be an absolute regular-file path"
    return 2
  fi
  [ -f "$_checksum" ] || {
    dsh_err "checksum file missing: $_checksum"
    return 1
  }
  _expected=$(dsh_image_expected_hash "$_checksum" "${_source##*/}")
  if [ "${#_expected}" -ne 64 ]; then
    dsh_err "checksum for ${_source##*/} not found in $_checksum"
    return 1
  fi
  case $_expected in
  *[!0-9a-f]*)
    dsh_err "invalid SHA256 in $_checksum"
    return 1
    ;;
  esac
  _actual=$(dsh_sha256 "$_source") || return 1
  [ "$_actual" = "$_expected" ] || {
    dsh_err "source image SHA256 mismatch"
    return 1
  }
  dsh_snapshot_require_cold || return 1
  dsh_snapshot_fsck_clean "$_source" || return 1
  mkdir -p "$DSH_IMAGE_DIR" || return 1
  chmod 0700 "$DSH_IMAGE_DIR" || return 1
  : >"$DSH_IMAGE_DIR/.nomedia" || return 1
  dsh_snapshot_require_space "$_source" "$DSH_STATE_DIR" || return 1
  _tmp=$DSH_IMAGE.import.$$.tmp
  dsh_cp_sparse "$_source" "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  [ "$(dsh_sha256 "$_tmp")" = "$_expected" ] || {
    rm -f "$_tmp"
    dsh_err "installed temporary image checksum mismatch"
    return 1
  }
  dsh_image_publish "$_tmp" "$_replace" || {
    rm -f "$_tmp"
    return 1
  }
  printf 'installed image: %s\nsha256: %s\n' "$DSH_IMAGE" "$_expected"
}

dsh_image_install() {
  local _apply=0 _replace=0 _source='' _checksum='' _arg _rc
  while [ "$#" -gt 0 ]; do
    _arg=$1
    case $_arg in
    --apply) _apply=1 ;;
    --replace) _replace=1 ;;
    --source)
      [ "$#" -ge 2 ] || {
        dsh_err "--source requires a file"
        return 2
      }
      _source=$2
      shift
      ;;
    --source=*) _source=${_arg#--source=} ;;
    --sha256)
      [ "$#" -ge 2 ] || {
        dsh_err "--sha256 requires a file"
        return 2
      }
      _checksum=$2
      shift
      ;;
    --sha256=*) _checksum=${_arg#--sha256=} ;;
    -h | --help)
      printf 'Usage: dsh-ctl image install --source FILE [--sha256 FILE] [--replace] --apply\n'
      return 0
      ;;
    *)
      dsh_err "unknown image option: $_arg"
      return 2
      ;;
    esac
    shift
  done
  [ -n "$_source" ] || {
    dsh_err "image install requires --source FILE"
    return 2
  }
  [ -n "$_checksum" ] || _checksum=$_source.sha256
  if [ "$_apply" -ne 1 ]; then
    _preview_size=$(stat -c %s "$_source" 2>/dev/null || true)
    [ -n "$_preview_size" ] || _preview_size=unknown
    _preview_sha=$(dsh_sha256 "$_source" 2>/dev/null || true)
    [ -n "$_preview_sha" ] || _preview_sha=unavailable
    printf 'image: would verify and install %s\nsize: %s bytes\nsha256: %s\n(use --apply)\n' "$_source" "$_preview_size" "$_preview_sha"
    return 3
  fi
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" = 1 ]; then
    dsh_image_install_claimed "$_source" "$_checksum" "$_replace"
  else
    dsh_image_claim_exec image-install "$_source" "$_checksum" "$_replace"
  fi
}

dsh_image_install_claimed() {
  local _rc
  dsh_lock_acquire || return 1
  if ! dsh_config_check_paths || ! mkdir -p "$DSH_IMAGE_DIR" || ! chmod 0700 "$DSH_IMAGE_DIR" || ! : >"$DSH_IMAGE_DIR/.nomedia"; then
    dsh_lock_release
    return 1
  fi
  dsh_doctor_run --for-apply --image-source="$1" && dsh_image_install_locked "$1" "$2" "$3"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_image_create_claimed() {
  local _rc
  dsh_lock_acquire || return 1
  dsh_image_create_locked "$1" "$2"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}
