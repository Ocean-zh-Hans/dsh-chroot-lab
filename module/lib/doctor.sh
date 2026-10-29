# shellcheck shell=sh disable=SC3043
# Environment checks never mount, unshare, or modify Android system state.
# The only write is a create/execute/delete probe in a private directory under DSH_TMP.

DSH_DOC_PASS=0
DSH_DOC_WARN=0
DSH_DOC_FAIL=0
DSH_DOC_STRICT=0

dsh_doc_pass() {
  DSH_DOC_PASS=$((DSH_DOC_PASS + 1))
  printf '[PASS] %s\n' "$*"
}

dsh_doc_warn() {
  DSH_DOC_WARN=$((DSH_DOC_WARN + 1))
  printf '[WARN] %s\n' "$*"
}

dsh_doc_fail() {
  DSH_DOC_FAIL=$((DSH_DOC_FAIL + 1))
  printf '[FAIL] %s\n' "$*"
}

dsh_doc_required() {
  if [ "$DSH_DOC_STRICT" -eq 1 ]; then
    dsh_doc_fail "$*"
  else
    dsh_doc_warn "$*"
  fi
}

dsh_doc_check_root() {
  _uid=$(id -u 2>/dev/null || printf '?')
  if [ "$_uid" = 0 ]; then
    dsh_doc_pass "running as uid 0"
  elif [ "${DSH_TEST_ALLOW_NONROOT:-0}" = 1 ]; then
    dsh_doc_warn "running as uid $_uid (test override; production requires root)"
  else
    dsh_doc_fail "running as uid $_uid, expected 0"
  fi
}

dsh_doc_check_selinux() {
  _f=$DSH_SYS/fs/selinux/enforce
  if [ -r "$_f" ]; then
    _v=$(dsh_read_line "$_f" 2>/dev/null || true)
    if [ "$_v" = 1 ]; then
      dsh_doc_pass "global SELinux enforce=1"
    else
      dsh_doc_fail "global SELinux enforce=${_v:-unreadable}, expected 1"
    fi
  else
    dsh_doc_fail "cannot read $_f"
  fi

  _ctx_f=$DSH_PROC/self/attr/current
  if [ -r "$_ctx_f" ]; then
    _ctx=$(dsh_read_line "$_ctx_f" 2>/dev/null || true)
    case $_ctx in
    u:r:ksu:s0 | u:r:magisk:s0) dsh_doc_pass "domain is $_ctx (root-manager domain; not a security boundary)" ;;
    '') dsh_doc_warn "domain context is empty" ;;
    *) dsh_doc_warn "domain is $_ctx (expected u:r:ksu:s0 on this device)" ;;
    esac
  else
    dsh_doc_warn "cannot read $_ctx_f"
  fi
}

dsh_doc_check_tmp() {
  if [ -d "$DSH_TMP" ] && [ -w "$DSH_TMP" ]; then
    dsh_doc_pass "host tmp exists and is writable: $DSH_TMP"
    _probe=$(mktemp -d "$DSH_TMP/dsh-doctor.XXXXXX" 2>/dev/null || true)
    if [ -n "$_probe" ] && [ -x "${DSH_HOST_TRUE:-/system/bin/true}" ] &&
      cp "${DSH_HOST_TRUE:-/system/bin/true}" "$_probe/true" && chmod 0700 "$_probe/true" && "$_probe/true"; then
      dsh_doc_pass "host tmp permits executable files"
    else
      dsh_doc_fail "host tmp does not permit executable files: $DSH_TMP"
    fi
    [ -z "$_probe" ] || rm -rf "$_probe"
  else
    dsh_doc_fail "host tmp missing/not writable: $DSH_TMP"
  fi

  if [ -d "$DSH_ROOTFS_DIR" ]; then
    if [ -n "$(ls -A "$DSH_ROOTFS_DIR" 2>/dev/null || true)" ]; then
      dsh_doc_fail "rootfs mountpoint is non-empty (possible leftover): $DSH_ROOTFS_DIR"
    else
      dsh_doc_pass "rootfs mountpoint exists and is empty: $DSH_ROOTFS_DIR"
    fi
  else
    dsh_doc_warn "rootfs mountpoint will be created at start: $DSH_ROOTFS_DIR"
    if [ -w "$(dirname "$DSH_ROOTFS_DIR")" ]; then
      dsh_doc_pass "parent of rootfs mountpoint is writable"
    else
      dsh_doc_fail "parent of rootfs mountpoint is not writable: $(dirname "$DSH_ROOTFS_DIR")"
    fi
  fi
}

dsh_doc_check_media() {
  if [ -d "$DSH_MEDIA" ]; then
    dsh_doc_pass "media root exists: $DSH_MEDIA"
  else
    dsh_doc_fail "media root missing: $DSH_MEDIA"
  fi

  if [ -d "$DSH_IMAGE_DIR" ]; then
    if dsh_mode_private "$DSH_IMAGE_DIR"; then
      dsh_doc_pass "image dir is private (no group/other bits): $DSH_IMAGE_DIR"
    else
      dsh_doc_fail "image dir is not 0700-like: $DSH_IMAGE_DIR (mode $(dsh_file_mode "$DSH_IMAGE_DIR" || true))"
    fi
    if [ -f "$DSH_IMAGE_DIR/.nomedia" ]; then
      dsh_doc_pass "image dir has .nomedia"
    else
      dsh_doc_required "image dir has no .nomedia: $DSH_IMAGE_DIR"
    fi
  else
    dsh_doc_required "image dir does not exist: $DSH_IMAGE_DIR"
  fi
}

dsh_doc_check_image() {
  if [ ! -e "$DSH_DOC_IMAGE" ]; then
    dsh_doc_required "rootfs image missing: $DSH_DOC_IMAGE"
    return
  fi
  if [ ! -f "$DSH_DOC_IMAGE" ]; then
    dsh_doc_fail "rootfs image is not a regular file: $DSH_DOC_IMAGE"
    return
  fi
  if ! dsh_mode_private "$DSH_DOC_IMAGE"; then
    dsh_doc_fail "rootfs image is not 0600-like: $DSH_DOC_IMAGE (mode $(dsh_file_mode "$DSH_DOC_IMAGE" || true))"
  else
    dsh_doc_pass "rootfs image is private: $DSH_DOC_IMAGE"
  fi
  _size=$(dsh_file_size "$DSH_DOC_IMAGE" || true)
  if dsh_is_positive_uint "$_size"; then
    dsh_doc_pass "rootfs image size: $_size bytes (apparent; sparse ext4)"
  else
    dsh_doc_fail "rootfs image size is zero/unreadable: $DSH_DOC_IMAGE"
  fi
}

dsh_doc_check_layout() {
  if [ "${DSH_TEST_ALLOW_UNUSUAL_PATHS:-0}" = 1 ]; then
    dsh_doc_warn "layout checks skipped by test override"
    return
  fi
  case $DSH_ROOTFS_DIR in
  /data | /data/* | /mnt | /mnt/* | /storage | /storage/* | /sdcard | /sdcard/*)
    dsh_doc_fail "rootfs mountpoint must not be under /data,/mnt,/storage,/sdcard: $DSH_ROOTFS_DIR"
    ;;
  *)
    dsh_doc_pass "rootfs mountpoint is outside /data,/mnt,/storage,/sdcard"
    ;;
  esac
  case $DSH_IMAGE in
  "$DSH_MEDIA"/*) dsh_doc_pass "image is under media root: $DSH_IMAGE" ;;
  *) dsh_doc_required "image is outside DSH_MEDIA; loop/FBE may fail: $DSH_IMAGE" ;;
  esac
}

dsh_doc_check_state() {
  if [ -d "$DSH_ROOT" ]; then
    if dsh_mode_private "$DSH_ROOT"; then
      dsh_doc_pass "module data dir is private: $DSH_ROOT"
    else
      dsh_doc_fail "module data dir is not 0700-like: $DSH_ROOT (mode $(dsh_file_mode "$DSH_ROOT" || true))"
    fi
  else
    dsh_doc_required "module data dir does not exist: $DSH_ROOT"
  fi

  if [ -d "$DSH_CONTROL_DIR" ]; then
    dsh_doc_pass "control dir exists: $DSH_CONTROL_DIR"
  else
    dsh_doc_required "control dir missing (customize.sh should create it): $DSH_CONTROL_DIR"
  fi

  if [ -f "$DSH_CONFIG" ]; then
    if dsh_mode_private "$DSH_CONFIG"; then
      dsh_doc_pass "config file is private: $DSH_CONFIG"
    else
      dsh_doc_warn "config file mode is not 0600-like: $DSH_CONFIG"
    fi
  fi
}

dsh_doc_check_tools() {
  _required="chroot mount umount nsenter setsid unshare sha256sum sync mke2fs e2fsck debugfs mountpoint cp tar truncate timeout mktemp"
  for _c in $_required; do
    if dsh_tool_path "$_c" >/dev/null 2>&1; then
      dsh_doc_pass "command present: $_c"
    else
      dsh_doc_required "command missing: $_c"
    fi
  done

  _cp=$(dsh_tool_path cp || true)
  if [ -n "$_cp" ] && "$_cp" --help 2>&1 | grep -q -- '--sparse=always'; then
    dsh_doc_pass "sparse copy: cp --sparse=always"
  else
    dsh_doc_required "cp --sparse=always is required"
  fi

  _losetup=''
  if [ -x "$DSH_MODULE_DIR/tools/bin/losetup" ]; then
    _losetup=$DSH_MODULE_DIR/tools/bin/losetup
  elif dsh_cmd_exists losetup; then
    _losetup=$(command -v losetup)
  fi
  if [ -n "$_losetup" ]; then
    if "$_losetup" --help 2>&1 | grep -q -- '--sector-size'; then
      dsh_doc_pass "losetup supports --sector-size: $_losetup"
    else
      dsh_doc_warn "losetup lacks --sector-size; using kernel default sector size: $_losetup"
    fi
  else
    dsh_doc_required "losetup missing"
  fi

  if [ -n "$_losetup" ] && "$_losetup" --help 2>&1 | grep -q -- '--sector-size'; then
    if "$_losetup" --help 2>&1 | grep -q -- '--direct-io'; then
      dsh_doc_pass "losetup supports --direct-io"
    else
      dsh_doc_fail "losetup --sector-size path requires --direct-io=off"
    fi
  fi
  _timeout=$(dsh_tool_path timeout || true)
  if [ -n "$_timeout" ] && "$_timeout" --help 2>&1 | grep -q -- '-k'; then
    dsh_doc_pass "timeout supports bounded guest commands"
  else
    dsh_doc_required "timeout with -k support is required"
  fi
  for _file in "$DSH_MODULE_DIR/bin/dsh-recover-mounts" "$DSH_CONTROL_DIR/bin/dsh-android-env" "$DSH_CONTROL_DIR/bin/dsh-healthcheck.py" "$DSH_CONTROL_DIR/bin/dsh-run"; do
    if [ -r "$_file" ]; then
      dsh_doc_pass "runtime dependency present: $_file"
    else
      dsh_doc_required "runtime dependency missing: $_file"
    fi
  done
  if [ -x "$DSH_CONTROL_DIR/bin/dsh-run" ]; then
    dsh_doc_pass "dsh-run is executable"
  else
    dsh_doc_required "dsh-run is missing or not executable"
  fi
  [ ! -e "$DSH_WATCHDOG_DISABLED" ] || dsh_doc_warn "watchdog is disabled; use dsh-watchdog --enable to allow claims"
  [ ! -e "$DSH_PURGE_MARKER" ] || dsh_doc_warn "watchdog purge is in progress; claims are disabled"

  _nsenter=$(dsh_tool_path nsenter || true)
  if [ -n "$_nsenter" ]; then
    dsh_doc_pass "nsenter present: $_nsenter; --mount=FILE/-t fallback is validated on use"
  fi
  _unshare=$(dsh_tool_path unshare || true)
  if [ -n "$_unshare" ]; then
    dsh_doc_pass "unshare present: $_unshare"
  fi

  if [ -x "$DSH_NSINIT" ]; then
    dsh_doc_pass "dsh-nsinit present: $DSH_NSINIT"
  else
    dsh_doc_fail "dsh-nsinit missing/not executable: $DSH_NSINIT"
  fi
  if [ -x "$DSH_WATCHDOG" ]; then
    dsh_doc_pass "dsh-watchdog present: $DSH_WATCHDOG"
  else
    dsh_doc_required "dsh-watchdog missing/not executable: $DSH_WATCHDOG"
  fi
  if [ -x "$DSH_RECURSIVE_RO" ]; then
    dsh_doc_pass "recursive-ro helper present: $DSH_RECURSIVE_RO"
  else
    dsh_doc_required "recursive-ro helper missing (needed by 6B/14B): $DSH_RECURSIVE_RO"
  fi
}

dsh_doc_check_guest() {
  if mountpoint -q "$DSH_ROOTFS_DIR" 2>/dev/null || [ -n "$(dsh_loop_attached_images "$DSH_DOC_IMAGE" 2>/dev/null)" ]; then
    dsh_doc_warn "guest image inspection skipped while the image is active"
    return 0
  fi
  _debugfs=$(dsh_tool_path debugfs || true)
  [ -n "$_debugfs" ] || {
    dsh_doc_required "debugfs is needed to inspect the unmounted guest image"
    return 0
  }
  _guest_stat=$("$_debugfs" -R 'stat /usr/bin/python3' "$DSH_DOC_IMAGE" 2>&1) || {
    dsh_doc_required "cannot inspect /usr/bin/python3 in the guest image"
    return 0
  }
  _python_link=$(printf '%s\n' "$_guest_stat" | sed -n 's/.*Fast link dest: "\([^"]*\)".*/\1/p' | head -n 1)
  case $_python_link in
  '') ;;
  python3.[0-9]*)
    _guest_stat=$("$_debugfs" -R "stat /usr/bin/$_python_link" "$DSH_DOC_IMAGE" 2>&1) || _guest_stat=
    ;;
  *) _guest_stat= ;;
  esac
  if printf '%s\n' "$_guest_stat" | grep -q 'Type: regular' &&
    printf '%s\n' "$_guest_stat" | grep -Eq 'Mode: +0[0-7]*[1357][0-7][0-7]'; then
    dsh_doc_pass "guest Python exists and is executable"
  else
    dsh_doc_required "guest /usr/bin/python3 is missing or not executable"
  fi
  if "$_debugfs" -R 'stat /tmp' "$DSH_DOC_IMAGE" 2>&1 | grep -q 'Type: directory'; then
    dsh_doc_pass "guest /tmp exists (mounted with exec-enabled tmpfs at start)"
  else
    dsh_doc_required "guest /tmp is missing from the image"
  fi
  if [ -x "$DSH_RUNTIME_DIR/current/bin/dsh" ]; then
    dsh_doc_pass "host DSH executable is present"
  else
    _runtime_stat=$("$_debugfs" -R 'stat /opt/dsh/current/bin/dsh' "$DSH_DOC_IMAGE" 2>&1) || {
      dsh_doc_required "host and baked DSH executables are unavailable"
      return 0
    }
    if printf '%s\n' "$_runtime_stat" | grep -q 'Type: regular' &&
      printf '%s\n' "$_runtime_stat" | grep -Eq 'Mode: +0[0-7]*[1357][0-7][0-7]'; then
      dsh_doc_pass "baked DSH executable is present"
    else
      dsh_doc_required "host and baked DSH executables are unavailable"
    fi
  fi
}

dsh_doc_check_mounts() {
  if dsh_mountinfo_has_tree "$DSH_ROOTFS_DIR"; then
    dsh_doc_required "host mountinfo already contains the rootfs mountpoint or a child: $DSH_ROOTFS_DIR"
  else
    _mount_check_rc=$?
    if [ "$_mount_check_rc" -eq 1 ]; then
      dsh_doc_pass "no rootfs mount in current namespace"
    else
      dsh_doc_required "cannot verify rootfs mounts in mountinfo"
    fi
  fi

  _private_mount_rc=0
  _private_mounts=$(dsh_proc_any_rootfs "$DSH_ROOTFS_DIR") || _private_mount_rc=$?
  case ${_private_mount_rc:-0} in
  0) dsh_doc_required "rootfs remains in process namespaces: $_private_mounts" ;;
  1) dsh_doc_pass "no rootfs mount in readable process namespaces" ;;
  *) dsh_doc_required "cannot verify rootfs mounts in process namespaces" ;;
  esac

  if [ -r "$DSH_MOUNTINFO" ] && grep -Eq 'hidepid=(invisible|2)([ ,]|$)' "$DSH_MOUNTINFO" 2>/dev/null; then
    dsh_doc_pass "host /proc uses hidepid=invisible or hidepid=2"
  else
    dsh_doc_required "cannot confirm hidepid=invisible or hidepid=2 from mountinfo"
  fi
}

dsh_doc_check_loop() {
  _doc_main_seen=0
  for _image in "$DSH_IMAGE" "$DSH_IMAGE_DIR"/rootfs*.img "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img; do
    [ -f "$_image" ] || continue
    if [ "$_image" = "$DSH_IMAGE" ]; then
      [ "$_doc_main_seen" -eq 0 ] || continue
      _doc_main_seen=1
    fi
    if ! _attached=$(dsh_loop_attached_images "$_image"); then
      dsh_doc_required "cannot query loop attachments: $_image"
    elif [ -n "$_attached" ]; then
      dsh_doc_required "image is already attached: $_image -> $_attached"
    else
      dsh_doc_pass "image has no attached loop: $_image"
    fi
  done
}

dsh_doc_ksu_feature_enabled() {
  printf '%s\n' "$2" | awk -v name="$1" '
    {
      for (i = 1; i <= NF; i++) {
        field = $i
        sub(/:$/, "", field)
        if (field != name) continue
        if (tolower($0) ~ /disabled/) disabled = 1
        if ($0 ~ /(^|[[:space:]\[])ENABLED([[:space:]]|$)/ ||
            $0 ~ /(^|[[:space:]\[])enabled([[:space:]]|$)/) enabled = 1
      }
    }
    END { exit !(enabled && !disabled) }'
}

dsh_doc_check_ksu() {
  _ksud=${DSH_KSUD:-/data/adb/ksud}
  if [ ! -x "$_ksud" ]; then
    dsh_doc_warn "ksud not found; cannot verify KSU hide features"
    return
  fi
  if [ "$(id -u 2>/dev/null || printf '?')" != 0 ]; then
    dsh_doc_warn "not root; skipped ksud feature check"
    return
  fi
  _out=$("$_ksud" feature list 2>/dev/null || true)
  if [ -z "$_out" ]; then
    dsh_doc_warn "ksud feature list returned no output"
    return
  fi
  for _f in su_compat kernel_umount selinux_hide mount_hide avc_spoof; do
    if dsh_doc_ksu_feature_enabled "$_f" "$_out"; then
      dsh_doc_pass "KSU feature enabled: $_f"
    else
      dsh_doc_fail "KSU feature not enabled: $_f"
    fi
  done
}

dsh_doctor_run() {
  DSH_DOC_PASS=0
  DSH_DOC_WARN=0
  DSH_DOC_FAIL=0
  DSH_DOC_STRICT=0
  DSH_DOC_IMAGE=$DSH_IMAGE
  for _arg in "$@"; do
    case $_arg in
    --for-apply) DSH_DOC_STRICT=1 ;;
    --image-source=*) DSH_DOC_IMAGE=${_arg#--image-source=} ;;
    -h | --help)
      dsh_doctor_help
      return 0
      ;;
    *)
      dsh_err "unknown doctor option: $_arg"
      return 2
      ;;
    esac
  done

  printf 'DSH Ubuntu Chroot doctor %s\n' "$DSH_VERSION"
  if [ "$DSH_DOC_STRICT" -eq 1 ]; then
    printf 'Mode: apply-readiness (missing apply dependencies are FAIL)\n'
  else
    printf 'Mode: static/read-only (missing apply dependencies are WARN)\n'
  fi
  printf 'Device root: %s\n\n' "$DSH_ROOT"

  dsh_config_check_paths || dsh_doc_fail "configuration paths are invalid"
  dsh_doc_check_root
  dsh_doc_check_selinux
  dsh_doc_check_tmp
  dsh_doc_check_layout
  dsh_doc_check_media
  dsh_doc_check_image
  dsh_doc_check_state
  dsh_doc_check_tools
  dsh_doc_check_mounts
  dsh_doc_check_loop
  dsh_doc_check_guest
  dsh_doc_check_ksu

  printf '\nSummary: %d pass, %d warn, %d fail\n' "$DSH_DOC_PASS" "$DSH_DOC_WARN" "$DSH_DOC_FAIL"
  if [ "$DSH_DOC_FAIL" -gt 0 ]; then
    return 4
  fi
  return 0
}

dsh_doctor_help() {
  cat <<'EOF'
Usage: dsh-ctl doctor [--for-apply] [--image-source=PATH]

Checks never mount, unshare, or change Android state. A temporary executable
probe is created and removed under DSH_TMP.

  --for-apply       treat missing apply dependencies as FAIL
  --image-source    inspect this image for installation, not an existing target
EOF
}
