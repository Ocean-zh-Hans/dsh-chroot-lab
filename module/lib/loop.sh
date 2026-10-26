# shellcheck shell=sh disable=SC3043
# Loop device helpers. All commands are read-only except attach/detach.

dsh_loop_attached_image() {
  _img=$1
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || return 1
  "$_losetup" -j "$_img" 2>/dev/null | sed -n 's/^\([^:]*\):.*/\1/p' | head -n 1
}

dsh_loop_attach() {
  _img=$1
  [ -f "$_img" ] || {
    dsh_err "image missing: $_img"
    return 1
  }
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || {
    dsh_err "losetup not found"
    return 1
  }
  _existing=$("$_losetup" -j "$_img" 2>/dev/null | sed -n 's/^\([^:]*\):.*/\1/p' | head -n 1 || true)
  if [ -n "$_existing" ]; then
    dsh_err "image already attached: $_existing ($_img)"
    return 1
  fi
  if "$_losetup" --help 2>&1 | grep -q -- '--sector-size'; then
    _loop=$("$_losetup" -f --show --sector-size 4096 --direct-io=off "$_img" 2>/dev/null | tail -n 1)
  else
    # Android toybox losetup: no --sector-size/--direct-io. The ext4 block
    # size still comes from mkfs; the loop sector size stays at the kernel
    # default. Doctor reports this as a WARN.
    _loop_out=$("$_losetup" -f --show "$_img" 2>/dev/null || "$_losetup" -f -s "$_img" 2>/dev/null)
    _loop=$(printf '%s\n' "$_loop_out" | tail -n 1)
  fi
  case $_loop in
  /dev/*) ;;
  *)
    if [ "${DSH_TEST_ALLOW_NON_DEV_LOOP:-0}" != 1 ]; then
      dsh_err "losetup did not return a loop device: ${_loop:-empty}"
      return 1
    fi
    ;;
  esac
  printf '%s\n' "$_loop"
}

dsh_loop_detach() {
  _loop=$1
  [ -n "$_loop" ] || return 0
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || return 1
  "$_losetup" -d "$_loop" 2>/dev/null || true
  _i=0
  while [ "$_i" -lt 50 ]; do
    "$_losetup" "$_loop" >/dev/null 2>&1 || return 0
    sleep 0.1 2>/dev/null || sleep 1
    _i=$((_i + 1))
  done
  dsh_err "loop still attached: $_loop"
  return 1
}

dsh_loop_detach_image() {
  _img=$1
  _loop=$(dsh_loop_attached_image "$_img" || true)
  [ -n "$_loop" ] || return 0
  dsh_loop_detach "$_loop"
}

dsh_loop_assert_image_free() {
  _img=$1
  _loop=$(dsh_loop_attached_image "$_img" || true)
  if [ -n "$_loop" ]; then
    dsh_err "image is already attached: $_loop"
    return 1
  fi
  return 0
}

dsh_e2fsck_prepare() {
  _img=$1
  _e2fsck=$(dsh_tool_path e2fsck || true)
  [ -n "$_e2fsck" ] || {
    dsh_err "e2fsck not found"
    return 1
  }
  _rc=0
  "$_e2fsck" -p "$_img" || _rc=$?
  case $_rc in
  0 | 1) return 0 ;;
  *)
    dsh_err "e2fsck failed with rc=$_rc: $_img"
    return 1
    ;;
  esac
}
