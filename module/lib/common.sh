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

dsh_is_positive_uint() {
  case ${1:-} in
  '' | *[!0-9]*) return 1 ;;
  *[1-9]*) return 0 ;;
  *) return 1 ;;
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

dsh_tool_path() {
  # Prefer a module-bundled tool over the host PATH.
  if [ -n "${DSH_MODULE_DIR:-}" ] && [ -x "$DSH_MODULE_DIR/tools/bin/$1" ]; then
    printf '%s\n' "$DSH_MODULE_DIR/tools/bin/$1"
    return 0
  fi
  command -v "$1" 2>/dev/null
}

dsh_bounded() {
  _bounded_seconds=$1
  shift
  dsh_is_uint "$_bounded_seconds" || return 2
  _bounded_timeout=$(dsh_tool_path timeout || true)
  [ -n "$_bounded_timeout" ] || {
    dsh_err "timeout is required for a bounded command"
    return 124
  }
  "$_bounded_timeout" -k 2 "$_bounded_seconds" "$@"
}

dsh_json_escape() {
  printf '%s' "${1:-}" | awk '
    BEGIN { ORS = "" }
    {
      if (NR > 1) printf "\\n"
      for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1)
        if (c == "\\") printf "%s%s", "\\", "\\"
        else if (c == "\"") printf "\\\""
        else if (c == "\t") printf "\\t"
        else if (c == "\r") printf "\\r"
        else if (c == "\b") printf "\\b"
        else if (c == "\f") printf "\\f"
        else printf "%s", c
      }
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

dsh_url_is_loopback() {
  case $1 in
  http://127.0.0.1:* | http://localhost:*) ;;
  *) return 1 ;;
  esac
  _rest=${1#http://}
  _port_path=${_rest#*:}
  _port=${_port_path%%[/?]*}
  case $_port in
  '' | *[!0-9]*) return 1 ;;
  esac
  [ "$_port" -ge 1 ] && [ "$_port" -le 65535 ]
}
