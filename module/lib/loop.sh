# shellcheck shell=sh disable=SC3043
# Loop helpers; image claims are advisory metadata, not block-device isolation.

dsh_image_claim_exec() {
  local _operation="$1" _helper
  shift
  _helper=$(dsh_tool_path dsh-mount-ro-recursive || true)
  [ -n "$_helper" ] || {
    dsh_err "image-claim helper missing: $DSH_RECURSIVE_RO"
    return 1
  }
  dsh_state_ensure || return 1
  DSH_IMAGE_CLAIM_ACTIVE=1 "$_helper" --claim "$DSH_IMAGE_CLAIM_FILE" \
    "${DSH_TEST_SHELL:-/system/bin/sh}" "$DSH_MODULE_DIR/bin/dsh-ctl" _image-claimed "$_operation" "$@"
}

dsh_loop_attached_images() {
  _img=$1
  [ -e "$_img" ] || return 0
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || return 1
  _out=$("$_losetup" -j "$_img" 2>/dev/null) || return 1
  printf '%s\n' "$_out" | sed -n 's/^\([^:]*\):.*/\1/p'
}

dsh_loop_attached_image() {
  _out=$(dsh_loop_attached_images "$1") || return 1
  printf '%s\n' "$_out" | sed -n '1p'
}

dsh_loop_device_id() {
  local _path="$1" _stat _id _name _number
  case $_path in
  /dev/block/loop[0-9]* | /dev/loop[0-9]*)
    _name=${_path##*/}
    _number=${_name#loop}
    if dsh_is_uint "$_number" && [ -d "${DSH_SYS:-/sys}/class/block/$_name" ]; then
      printf 'kernel:%s\n' "$_name"
      return 0
    fi
    ;;
  esac
  if [ -b "$_path" ]; then
    _stat=$(dsh_tool_path stat || true)
    [ -n "$_stat" ] || return 1
    _id=$("$_stat" -c '%t:%T' "$_path" 2>/dev/null) || return 1
    case $_id in
    *:*) ;;
    *) return 1 ;;
    esac
    printf 'block:%s\n' "$_id"
  else
    readlink -f "$_path" 2>/dev/null
  fi
}

dsh_loop_same_device() {
  local _left _right
  _left=$(dsh_loop_device_id "$1") || return 1
  _right=$(dsh_loop_device_id "$2") || return 1
  [ -n "$_left" ] && [ "$_left" = "$_right" ]
}

dsh_loop_matches_image() {
  local _loop="$1" _img="$2" _attached _attached_list
  _attached_list=$(dsh_loop_attached_images "$_img" 2>/dev/null) || return 1
  for _attached in $_attached_list; do
    if dsh_loop_same_device "$_attached" "$_loop"; then
      return 0
    fi
  done
  return 1
}

dsh_loop_unexpected_images() {
  local _recorded="$1" _img="$2" _attached _attached_list
  _attached_list=$(dsh_loop_attached_images "$_img") || return 1
  for _attached in $_attached_list; do
    if [ -z "$_recorded" ] || ! dsh_loop_same_device "$_attached" "$_recorded"; then
      printf '%s\n' "$_attached"
    fi
  done
}

dsh_loop_attach_explicit_free() {
  _img=$1
  _losetup=$2
  _last_error=$3
  # Device paths are intentionally a glob/list, not a single filename.
  # shellcheck disable=SC2086
  for _candidate in ${DSH_LOOP_CANDIDATES:-/dev/block/loop[0-9]*}; do
    [ -e "$_candidate" ] || continue
    _probe=$("$_losetup" "$_candidate" 2>&1)
    _probe_rc=$?
    [ "$_probe_rc" -eq 0 ] && continue
    case $_probe in
    *'No such file or directory' | *'No such device or address') ;;
    *) continue ;;
    esac
    if _attach_out=$("$_losetup" "$_candidate" "$_img" 2>&1); then
      printf '%s\n' "$_candidate"
      return 0
    fi
    _last_error=$_attach_out
    dsh_loop_assert_image_free "$_img" || return 1
  done
  dsh_err "no verified free loop device: ${_last_error:-empty}"
  return 1
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
  dsh_loop_assert_image_free "$_img" || return 1
  if "$_losetup" --help 2>&1 | grep -q -- '--sector-size'; then
    _loop=$("$_losetup" -f --show --sector-size 4096 --direct-io=off "$_img" 2>&1 | tail -n 1)
    case $_loop in
    /dev/*)
      printf '%s\n' "$_loop"
      return 0
      ;;
    esac
    if [ "${DSH_TEST_ALLOW_NON_DEV_LOOP:-0}" = 1 ] && [ -e "$_loop" ]; then
      printf '%s\n' "$_loop"
      return 0
    fi
    dsh_err "losetup did not return a loop device: ${_loop:-empty}"
    return 1
  fi
  # Android toybox losetup: no --sector-size/--direct-io. The ext4 block
  # size still comes from mkfs; the loop sector size stays at the kernel
  # default. Doctor reports this as a WARN.
  _loop_out=$("$_losetup" -f --show "$_img" 2>&1 || "$_losetup" -f -s "$_img" 2>&1)
  _loop=$(printf '%s\n' "$_loop_out" | tail -n 1)
  case $_loop in
  /dev/*)
    printf '%s\n' "$_loop"
    return 0
    ;;
  esac
  if [ "${DSH_TEST_ALLOW_NON_DEV_LOOP:-0}" = 1 ] && [ -e "$_loop" ]; then
    printf '%s\n' "$_loop"
    return 0
  fi
  dsh_loop_attach_explicit_free "$_img" "$_losetup" "$_loop_out"
}

# Attach, then persist the record atomically. If the record cannot be written,
# detach immediately; otherwise a later stop has no loop identity to clean up.
dsh_loop_attach_record() {
  _img=$1
  _record=$2
  [ -n "$_record" ] || {
    dsh_err "loop record path is empty"
    return 1
  }
  _loop=$(dsh_loop_attach "$_img") || return 1
  _record_tmp="${_record}.tmp.$$"
  if ! printf '%s\n' "$_loop" >"$_record_tmp" || ! mv -f "$_record_tmp" "$_record"; then
    rm -f "$_record_tmp"
    dsh_err "cannot record loop $_loop; detaching"
    if dsh_loop_detach "$_loop" "$_img"; then
      dsh_err "loop record failed; detached $_loop"
    else
      dsh_err "loop record failed and detach failed: $_loop"
    fi
    return 1
  fi
  printf '%s\n' "$_loop"
}

dsh_loop_detach() {
  local _loop="$1" _img="${2:-}" _losetup _owned _attached _attached_list _detach _i
  [ -n "$_loop" ] || return 0
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || return 1
  _detach=$_loop
  if [ -n "$_img" ]; then
    _owned=0
    _attached_list=$(dsh_loop_attached_images "$_img" 2>/dev/null) || {
      dsh_err "cannot verify loop ownership for $_img"
      return 1
    }
    for _attached in $_attached_list; do
      if dsh_loop_same_device "$_attached" "$_loop"; then
        _owned=1
        if [ ! -e "$_loop" ] && [ -e "$_attached" ]; then
          _detach=$_attached
        fi
      fi
    done
    if [ "$_owned" -ne 1 ]; then
      if "$_losetup" "$_loop" >/dev/null 2>&1; then
        dsh_err "refusing to detach a loop not backed by $_img: $_loop"
        return 1
      fi
      return 0
    fi
  elif ! "$_losetup" "$_loop" >/dev/null 2>&1; then
    return 0
  fi
  "$_losetup" -d "$_detach" 2>/dev/null || {
    dsh_err "losetup -d failed: $_detach"
    return 1
  }
  _i=0
  while [ "$_i" -lt 50 ]; do
    "$_losetup" "$_detach" >/dev/null 2>&1 || return 0
    sleep 0.1 2>/dev/null || sleep 1
    _i=$((_i + 1))
  done
  dsh_err "loop still attached: $_detach"
  return 1
}

dsh_loop_assert_image_free() {
  _img=$1
  if ! _loop=$(dsh_loop_attached_image "$_img"); then
    dsh_err "cannot query loop attachments: $_img"
    return 1
  fi
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
