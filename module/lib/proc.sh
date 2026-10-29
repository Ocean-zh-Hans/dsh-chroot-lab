# shellcheck shell=sh disable=SC3043
# Read-only /proc helpers. Never signal or modify processes here.

dsh_proc_stat_file() {
  printf '%s/%s/stat\n' "${DSH_PROC:-/proc}" "$1"
}

dsh_proc_starttime() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  _f=$(dsh_proc_stat_file "$_pid")
  [ -r "$_f" ] || return 1
  awk '{ sub(/^.*\) /, ""); print $20 }' "$_f" 2>/dev/null
}

dsh_proc_pgid() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  _f=$(dsh_proc_stat_file "$_pid")
  [ -r "$_f" ] || return 1
  awk '{ sub(/^.*\) /, ""); print $3 }' "$_f" 2>/dev/null
}

dsh_proc_ns() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  readlink "${DSH_PROC:-/proc}/$_pid/ns/mnt" 2>/dev/null
}

dsh_proc_alive() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  _f=$(dsh_proc_stat_file "$_pid")
  [ -r "$_f" ] || return 1
  _proc_stat=$(dsh_read_line "$_f" 2>/dev/null) || return 1
  _proc_rest=${_proc_stat##*) }
  [ "$_proc_rest" != "$_proc_stat" ] || return 1
  _proc_state=${_proc_rest%% *}
  case $_proc_state in
  '' | Z | X | x) return 1 ;;
  esac
  return 0
}

dsh_proc_cmdline() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  _f=${DSH_PROC:-/proc}/$_pid/cmdline
  [ -r "$_f" ] || return 1
  tr '\000' ' ' <"$_f" 2>/dev/null
}

dsh_boot_id() {
  dsh_read_line "${DSH_BOOT_ID_SOURCE:-${DSH_PROC:-/proc}/sys/kernel/random/boot_id}" 2>/dev/null
}

dsh_proc_validate_identity() {
  # Args: pid recorded_starttime recorded_ns
  _pid=${1:-}
  _st=${2:-}
  _ns=${3:-}
  dsh_proc_alive "$_pid" || return 1
  _actual_st=$(dsh_proc_starttime "$_pid") || return 1
  _actual_ns=$(dsh_proc_ns "$_pid") || return 1
  [ "$_actual_st" = "$_st" ] || return 1
  [ "$_actual_ns" = "$_ns" ] || return 1
  if [ -n "${DSH_BOOT_ID_FILE:-}" ] && [ -e "$DSH_BOOT_ID_FILE" ]; then
    _recorded_boot=$(dsh_read_line "$DSH_BOOT_ID_FILE" 2>/dev/null) || return 1
    _current_boot=$(dsh_boot_id) || return 1
    [ -n "$_recorded_boot" ] && [ "$_recorded_boot" = "$_current_boot" ] || return 1
  fi
  return 0
}

dsh_proc_scan_ns() {
  # Args: ns string. Prints pids in this mount namespace (read-only).
  _want=${1:-}
  [ -n "$_want" ] || return 1
  _proc=${DSH_PROC:-/proc}
  command -v ls >/dev/null 2>&1 && command -v awk >/dev/null 2>&1 || return 1
  if _proc_listing=$(ls -ld "$_proc"/[0-9]*/ns/mnt 2>/dev/null); then
    _scan_ls_rc=0
  else
    _scan_ls_rc=$?
  fi
  [ "$_scan_ls_rc" -le 1 ] || return "$_scan_ls_rc"
  printf '%s\n' "$_proc_listing" | awk -v ns="$_want" -v root="$_proc/" '
    $NF == ns {
      path = $(NF - 2)
      if (index(path, root) != 1) next
      path = substr(path, length(root) + 1)
      sub(/\/ns\/mnt$/, "", path)
      if (path ~ /^[0-9]+$/) print path
    }
  '
}

dsh_proc_ns_has_rootfs() {
  local _pid="${1:-}" _root="${2:-}" _info _root_link _info_err _info_rc
  dsh_is_uint "$_pid" || return 1
  [ -n "$_root" ] && [ "$_root" != / ] || return 1
  _root=${_root%/}
  _root_link=$(readlink "${DSH_PROC:-/proc}/$_pid/root" 2>/dev/null || true)
  case $_root_link in
  "$_root" | "$_root"/*) return 0 ;;
  esac
  _info=${DSH_PROC:-/proc}/$_pid/mountinfo
  if [ ! -e "$_info" ] && [ ! -L "$_info" ]; then return 1; fi
  if [ ! -f "$_info" ] || [ ! -r "$_info" ]; then
    if [ ! -e "$_info" ] && [ ! -L "$_info" ]; then return 1; fi
    return 2
  fi
  _info_err=$(awk -v root="$_root" '
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
  ' "$_info" 2>&1)
  _info_rc=$?
  case $_info_err in
  *'No such file or directory'*) return 1 ;;
  '') ;;
  *) return 2 ;;
  esac
  case $_info_rc in
  0 | 1) return "$_info_rc" ;;
  *) return 2 ;;
  esac
}

dsh_proc_scan_rootfs() {
  local _want_ns="${1:-}" _root="${2:-}" _proc="${DSH_PROC:-/proc}" _listing _ls_rc _result _awk_rc
  [ -n "$_root" ] && [ "$_root" != / ] || return 2
  _root=${_root%/}
  command -v ls >/dev/null 2>&1 && command -v awk >/dev/null 2>&1 || return 2
  _listing=$(ls -ld "$_proc"/[0-9]*/ns/mnt "$_proc"/[0-9]*/root \
    "$_proc"/[0-9]*/stat "$_proc"/[0-9]*/mountinfo 2>/dev/null)
  _ls_rc=$?
  [ "$_ls_rc" -le 1 ] && [ -n "$_listing" ] || return 2
  _result=$(
    awk -v proc="$_proc/" -v root="$_root" -v want_ns="$_want_ns" '
    function remember(pid) {
      if (!(pid in seen)) {
        seen[pid] = 1
        pids[++count] = pid
      }
    }
    function decode(path) {
      gsub(/\\040/, " ", path)
      gsub(/\\011/, "\t", path)
      gsub(/\\012/, "\n", path)
      gsub(/\\134/, "\\\\", path)
      return path
    }
    {
      path = $NF
      target = ""
      for (i = 1; i <= NF; i++) {
        if ($i == "->") {
          path = $(i - 1)
          for (j = i + 1; j <= NF; j++) target = target (target == "" ? "" : " ") $j
          break
        }
      }
      sub(/\r$/, "", path)
      sub(/\r$/, "", target)
      if (index(path, proc) != 1) next
      relative = substr(path, length(proc) + 1)
      split(relative, parts, "/")
      pid = parts[1]
      if (pid !~ /^[0-9]+$/) next
      kind = substr(relative, length(pid) + 2)
      if (kind != "ns/mnt" && kind != "root" && kind != "stat" && kind != "mountinfo") next
      remember(pid)
      if (kind == "ns/mnt") namespaces[pid] = target
      else if (kind == "root") roots[pid] = target
      else {
        files[pid, kind] = path
        if (substr($1, 1, 1) != "-") invalid[pid, kind] = 1
      }
    }
    END {
      for (n = 1; n <= count; n++) {
        pid = pids[n]
        if (!((pid, "stat") in files) || invalid[pid, "stat"]) {
          print "U:" pid
          continue
        }
        if (want_ns != "" && namespaces[pid] != "" && namespaces[pid] != want_ns) continue
        stat_path = files[pid, "stat"]
        if ((getline stat_line < stat_path) != 1) {
          print "U:" pid
          close(stat_path)
          continue
        }
        close(stat_path)
        rest = stat_line
        sub(/^.*\) /, "", rest)
        split(rest, stat_fields, " ")
        state = stat_fields[1]
        kernel_thread = int(stat_fields[7] / 2097152) % 2
        if (rest == stat_line || state !~ /^[A-Za-z]$/) {
          print "U:" pid
          continue
        }
        if (state == "Z" || state == "X" || state == "x") continue
        if (roots[pid] == root || index(roots[pid], root "/") == 1) {
          print "P:" pid
          continue
        }
        if (kernel_thread && namespaces[pid] == "") continue
        if (want_ns != "" && namespaces[pid] == "") {
          print "U:" pid
          continue
        }
        if (!((pid, "mountinfo") in files) || invalid[pid, "mountinfo"]) {
          print "U:" pid
          continue
        }
        info_path = files[pid, "mountinfo"]
        read_rc = getline line < info_path
        if (read_rc < 0) {
          print "U:" pid
          close(info_path)
          continue
        }
        found = 0
        while (read_rc > 0) {
          split(line, fields, " ")
          path = decode(fields[5])
          if (path == root || index(path, root "/") == 1) {
            found = 1
            break
          }
          read_rc = getline line < info_path
        }
        close(info_path)
        if (read_rc < 0) print "U:" pid
        else if (found) print "P:" pid
      }
    }
  ' 2>/dev/null <<EOF
$_listing
EOF
  )
  _awk_rc=$?
  [ "$_awk_rc" -eq 0 ] || return 2
  printf '%s\n' "$_result"
}

dsh_proc_any_rootfs() {
  local _root="${1:-}" _exclude="${2:-}" _scan _row _pid _found=0 _unknown=0
  [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ] || return 1
  _scan=$(dsh_proc_scan_rootfs '' "$_root") || return 2
  for _row in $_scan; do
    _pid=${_row#?:}
    case $_row in
    P:"$_exclude") continue ;;
    P:*)
      printf '%s\n' "$_pid"
      _found=1
      ;;
    U:*)
      if [ -e "${DSH_PROC:-/proc}/$_pid" ] || [ -L "${DSH_PROC:-/proc}/$_pid" ]; then
        dsh_warn "cannot inspect mount info for pid $_pid"
        _unknown=1
      fi
      ;;
    *) return 2 ;;
    esac
  done
  [ "$_unknown" -eq 0 ] || return 2
  [ "$_found" -eq 1 ]
}

dsh_proc_scan_rootfs_ns() {
  local _ns="${1:-}" _root="${2:-${DSH_ROOTFS_DIR:-}}" _scan _row _pid _unknown=0
  [ -n "$_ns" ] && [ -n "$_root" ] || return 2
  _scan=$(dsh_proc_scan_rootfs "$_ns" "$_root") || return 2
  for _row in $_scan; do
    _pid=${_row#?:}
    case $_row in
    P:*) printf '%s\n' "$_pid" ;;
    U:*)
      if [ -e "${DSH_PROC:-/proc}/$_pid" ] || [ -L "${DSH_PROC:-/proc}/$_pid" ]; then
        _unknown=1
      fi
      ;;
    *) return 2 ;;
    esac
  done
  [ "$_unknown" -eq 0 ] || return 2
  return 0
}
