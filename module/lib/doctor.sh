# shellcheck shell=sh disable=SC3043
# Read-only environment checks. This file must never mount, unshare, or write
# to system paths. State/log creation is owned by other commands.

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
    *u:r:ksu:s0*) dsh_doc_pass "domain is u:r:ksu:s0 (permissive allow-all; not a security boundary)" ;;
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
  if [ ! -e "$DSH_IMAGE" ]; then
    dsh_doc_required "rootfs image missing: $DSH_IMAGE"
    return
  fi
  if [ ! -f "$DSH_IMAGE" ]; then
    dsh_doc_fail "rootfs image is not a regular file: $DSH_IMAGE"
    return
  fi
  if ! dsh_mode_private "$DSH_IMAGE"; then
    dsh_doc_fail "rootfs image is not 0600-like: $DSH_IMAGE (mode $(dsh_file_mode "$DSH_IMAGE" || true))"
  else
    dsh_doc_pass "rootfs image is private: $DSH_IMAGE"
  fi
  _size=$(dsh_file_size "$DSH_IMAGE" || true)
  if dsh_is_uint "$_size" && [ "$_size" -gt 0 ]; then
    dsh_doc_pass "rootfs image size: $_size bytes (apparent; sparse ext4)"
  else
    dsh_doc_fail "rootfs image size is zero/unreadable: $DSH_IMAGE"
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
  _required="chroot mount umount nsenter setsid unshare sha256sum sync mke2fs e2fsck resize2fs mountpoint cp tar truncate"
  for _c in $_required; do
    if dsh_cmd_exists "$_c"; then
      dsh_doc_pass "command present: $_c"
    else
      dsh_doc_required "command missing: $_c"
    fi
  done

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
      dsh_doc_required "losetup lacks --sector-size (toybox?): $_losetup"
    fi
  else
    dsh_doc_required "losetup missing"
  fi

  _nsenter=$(dsh_tool_path nsenter || true)
  if [ -n "$_nsenter" ]; then
    if "$_nsenter" --help 2>&1 | grep -q -- '--mount'; then
      dsh_doc_pass "nsenter supports --mount: $_nsenter"
    else
      dsh_doc_required "nsenter lacks --mount (util-linux needed): $_nsenter"
    fi
  fi
  _unshare=$(dsh_tool_path unshare || true)
  if [ -n "$_unshare" ]; then
    if "$_unshare" --help 2>&1 | grep -q -- '--propagation'; then
      dsh_doc_pass "unshare supports --propagation: $_unshare"
    else
      dsh_doc_required "unshare lacks --propagation (util-linux needed): $_unshare"
    fi
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

dsh_doc_check_mounts() {
  if [ -r "$DSH_PROC/self/mountinfo" ] && grep -q 'dsh-rootfs' "$DSH_PROC/self/mountinfo" 2>/dev/null; then
    dsh_doc_required "host mountinfo already contains dsh-rootfs; possible leftover"
  else
    dsh_doc_pass "no dsh-rootfs mount in current namespace"
  fi

  if [ -r "$DSH_PROC/self/mountinfo" ] && grep -q 'hidepid=' "$DSH_PROC/self/mountinfo" 2>/dev/null; then
    dsh_doc_pass "host /proc mountinfo exposes hidepid setting"
  else
    dsh_doc_warn "cannot confirm hidepid=invisible from mountinfo"
  fi
}

dsh_doc_check_ksu() {
  if [ ! -x /data/adb/ksud ]; then
    dsh_doc_warn "ksud not found; cannot verify KSU hide features"
    return
  fi
  if [ "$(id -u 2>/dev/null || printf '?')" != 0 ]; then
    dsh_doc_warn "not root; skipped ksud feature check"
    return
  fi
  _out=$(/data/adb/ksud feature list 2>/dev/null || true)
  if [ -z "$_out" ]; then
    dsh_doc_warn "ksud feature list returned no output"
    return
  fi
  for _f in su_compat kernel_umount selinux_hide mount_hide avc_spoof; do
    if printf '%s\n' "$_out" | grep -q "${_f}[[:space:]].*ENABLED"; then
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
  for _arg in "$@"; do
    case $_arg in
    --for-apply) DSH_DOC_STRICT=1 ;;
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

  dsh_doc_check_root
  dsh_doc_check_selinux
  dsh_doc_check_tmp
  dsh_doc_check_layout
  dsh_doc_check_media
  dsh_doc_check_image
  dsh_doc_check_state
  dsh_doc_check_tools
  dsh_doc_check_mounts
  dsh_doc_check_ksu

  printf '\nSummary: %d pass, %d warn, %d fail\n' "$DSH_DOC_PASS" "$DSH_DOC_WARN" "$DSH_DOC_FAIL"
  if [ "$DSH_DOC_FAIL" -gt 0 ]; then
    return 4
  fi
  return 0
}

dsh_doctor_help() {
  cat <<'EOF'
Usage: dsh-ctl doctor [--for-apply]

Read-only checks. Never mounts, unshares, or writes system state.

  --for-apply   treat missing apply dependencies as FAIL
EOF
}
