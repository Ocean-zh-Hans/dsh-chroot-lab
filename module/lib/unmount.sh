# shellcheck shell=sh disable=SC3043

dsh_mountinfo_has_mount() {
  local _dut_path="${1:-}" _dut_info="${DSH_MOUNTINFO:-/proc/self/mountinfo}" _dut_check_rc
  [ -n "$_dut_path" ] && [ -r "$_dut_info" ] || return 2
  awk -v want="$_dut_path" '
    function decode(path) {
      gsub(/\\040/, " ", path)
      gsub(/\\011/, "\t", path)
      gsub(/\\012/, "\n", path)
      gsub(/\\134/, "\\\\", path)
      return path
    }
    {
      if (decode($5) == want) found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "$_dut_info" 2>/dev/null
  _dut_check_rc=$?
  case $_dut_check_rc in
  0 | 1) return "$_dut_check_rc" ;;
  *) return 2 ;;
  esac
}

dsh_mountinfo_has_tree() {
  local _tree_root="${1:-}" _tree_info="${DSH_MOUNTINFO:-/proc/self/mountinfo}" _tree_rc
  [ -n "$_tree_root" ] && [ "$_tree_root" != / ] && [ -r "$_tree_info" ] || return 2
  awk -v root="$_tree_root" '
    function decode(path) {
      gsub(/\\040/, " ", path)
      gsub(/\\011/, "\t", path)
      gsub(/\\012/, "\n", path)
      gsub(/\\134/, "\\\\", path)
      return path
    }
    {
      path = decode($5)
      if (path == root || index(path, root "/") == 1) found = 1
    }
    END { exit(found ? 0 : 1) }
  ' "$_tree_info" 2>/dev/null
  _tree_rc=$?
  case $_tree_rc in
  0 | 1) return "$_tree_rc" ;;
  *) return 2 ;;
  esac
}

dsh_unmount_tree() {
  local _dut_target="${1:-}" _dut_list="${2:-}" _dut_root="${3:-}"
  local _dut_info="${DSH_MOUNTINFO:-/proc/self/mountinfo}"
  local _dut_raw _dut_sorted _dut_path _dut_rc=0 _dut_mp_rc _umount_error
  [ -n "$_dut_target" ] && [ -n "$_dut_list" ] && [ -n "$_dut_root" ] || return 1
  [ "$_dut_root" != / ] && [ -r "$_dut_info" ] || return 1
  case $_dut_target in
  "$_dut_root" | "$_dut_root"/*) ;;
  *) return 1 ;;
  esac
  _dut_raw=$_dut_list.raw.$$
  _dut_sorted=$_dut_list.sorted.$$
  awk -v root="$_dut_target" '
    function decode(path) {
      gsub(/\\040/, " ", path)
      gsub(/\\011/, "\t", path)
      gsub(/\\012/, "\n", path)
      gsub(/\\134/, "\\\\", path)
      return path
    }
    {
      path = decode($5)
      if (path == root || index(path, root "/") == 1) {
        depth = gsub(/\//, "/", path)
        print depth " " path
      }
    }
  ' "$_dut_info" >"$_dut_raw" || {
    rm -f "$_dut_raw" "$_dut_sorted" "$_dut_list"
    return 1
  }
  sort -nr "$_dut_raw" >"$_dut_sorted" || {
    rm -f "$_dut_raw" "$_dut_sorted" "$_dut_list"
    return 1
  }
  sed -n 's/^[0-9][0-9]* //p' "$_dut_sorted" >"$_dut_list" || {
    rm -f "$_dut_raw" "$_dut_sorted" "$_dut_list"
    return 1
  }
  rm -f "$_dut_raw" "$_dut_sorted" || return 1
  while IFS= read -r _dut_path; do
    case $_dut_path in
    "$_dut_root" | "$_dut_root"/*) ;;
    *)
      _dut_rc=1
      continue
      ;;
    esac
    if _umount_error=$(umount "$_dut_path" 2>&1); then
      :
    elif dsh_mountinfo_has_mount "$_dut_path"; then
      if command -v dsh_log >/dev/null 2>&1; then
        dsh_log "umount failed: $_dut_path: $_umount_error"
      fi
      printf 'ERROR: umount failed: %s: %s\n' "$_dut_path" "$_umount_error" >&2
      _dut_rc=1
    else
      _dut_mp_rc=$?
      [ "$_dut_mp_rc" -eq 1 ] || _dut_rc=1
    fi
  done <"$_dut_list"
  rm -f "$_dut_list" || _dut_rc=1
  if dsh_mountinfo_has_mount "$_dut_target"; then
    _dut_rc=1
  else
    _dut_mp_rc=$?
    [ "$_dut_mp_rc" -eq 1 ] || _dut_rc=1
  fi
  return "$_dut_rc"
}
