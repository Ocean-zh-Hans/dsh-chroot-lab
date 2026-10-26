# shellcheck shell=sh disable=SC3043
# First-install image creation from a staging rootfs directory.

dsh_image_create() {
  _apply=0
  _source=$DSH_ROOTFS_STAGING
  _size=${DSH_IMAGE_SIZE_GB}G
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
    printf 'image: would create %s from %s (use --apply)\n' "$DSH_IMAGE" "$_source"
    return 3
  fi
  [ -d "$_source" ] || {
    dsh_err "staging rootfs missing: $_source"
    return 1
  }
  [ -e "$DSH_IMAGE" ] && {
    dsh_err "image already exists: $DSH_IMAGE"
    return 1
  }
  mkdir -p "$DSH_IMAGE_DIR" || return 1
  chmod 0700 "$DSH_IMAGE_DIR" 2>/dev/null || true
  : >"$DSH_IMAGE_DIR/.nomedia" 2>/dev/null || true
  _truncate=$(dsh_tool_path truncate || true)
  _mke2fs=$(dsh_tool_path mke2fs || true)
  _e2fsck=$(dsh_tool_path e2fsck || true)
  [ -n "$_truncate" ] && [ -n "$_mke2fs" ] && [ -n "$_e2fsck" ] || {
    dsh_err "truncate/mke2fs/e2fsck missing"
    return 1
  }
  "$_truncate" -s "$_size" "$DSH_IMAGE" || return 1
  "$_mke2fs" -t ext4 -b 4096 -d "$_source" "$DSH_IMAGE" || {
    rm -f "$DSH_IMAGE"
    return 1
  }
  "$_e2fsck" -p "$DSH_IMAGE" || {
    _rc=$?
    [ "$_rc" -eq 1 ] || {
      dsh_err "e2fsck failed: $_rc"
      return 1
    }
  }
  chmod 0600 "$DSH_IMAGE" 2>/dev/null || true
  printf 'created image: %s\nsize: %s\n' "$DSH_IMAGE" "$_size"
}
