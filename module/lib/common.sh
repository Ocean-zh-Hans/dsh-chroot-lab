# shellcheck shell=sh disable=SC3043
# Common helpers shared by all dsh-ctl commands. POSIX sh + mksh compatible.

DSH_PROG=${DSH_PROG:-dsh-ctl}
DSH_VERSION=${DSH_VERSION:-0.1.0}

dsh_info() {
  printf '%s\n' "$*"
}

dsh_warn() {
  printf 'WARN: %s\n' "$*" >&2
}

dsh_err() {
  printf 'ERROR: %s\n' "$*" >&2
}

dsh_die() {
  dsh_err "$*"
  exit 2
}

dsh_is_uint() {
  case ${1:-} in
  '' | *[!0-9]*) return 1 ;;
  *) return 0 ;;
  esac
}

dsh_is_abs() {
  case ${1:-} in
  /*) return 0 ;;
  *) return 1 ;;
  esac
}

dsh_cmd_exists() {
  command -v "$1" >/dev/null 2>&1
}

dsh_json_escape() {
  printf '%s' "${1:-}" | awk '
    BEGIN { ORS = "" }
    {
      gsub(/\\/, "\\\\")
      gsub(/"/, "\\\"")
      gsub(/\t/, "\\t")
      gsub(/\r/, "\\r")
      if (NR > 1) printf "\\n"
      printf "%s", $0
    }
  '
}

# Print a file's octal mode. Returns 1 if unavailable.
dsh_file_mode() {
  if stat -c %a "$1" 2>/dev/null; then
    return 0
  fi
  if stat -f %Lp "$1" 2>/dev/null; then
    return 0
  fi
  return 1
}

# True if group/other permission bits are all zero.
dsh_mode_private() {
  _mode=$(dsh_file_mode "$1") || return 1
  case $_mode in
  '' | *[!0-7]*) return 1 ;;
  esac
  case $_mode in
  *00) return 0 ;;
  *) return 1 ;;
  esac
}

dsh_file_size() {
  if stat -c %s "$1" 2>/dev/null; then
    return 0
  fi
  if stat -f %z "$1" 2>/dev/null; then
    return 0
  fi
  return 1
}

dsh_read_line() {
  # Read at most one line; never fail the caller when the file is absent.
  [ -r "$1" ] || return 1
  IFS= read -r _line <"$1" || [ -n "${_line:-}" ] || return 1
  printf '%s\n' "$_line"
}

dsh_trim() {
  printf '%s' "${1:-}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

dsh_bool() {
  case ${1:-} in
  1 | yes | true | on) return 0 ;;
  *) return 1 ;;
  esac
}
