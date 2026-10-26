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
  [ -r "$(dsh_proc_stat_file "$_pid")" ]
}

dsh_proc_cmdline() {
  _pid=${1:-}
  dsh_is_uint "$_pid" || return 1
  _f=${DSH_PROC:-/proc}/$_pid/cmdline
  [ -r "$_f" ] || return 1
  tr '\000' ' ' <"$_f" 2>/dev/null
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
  return 0
}

dsh_proc_scan_ns() {
  # Args: ns string. Prints pids in this mount namespace (read-only).
  _want=${1:-}
  [ -n "$_want" ] || return 1
  _proc=${DSH_PROC:-/proc}
  for _p in "$_proc"/[0-9]*; do
    [ -d "$_p" ] || continue
    _pid=${_p##*/}
    [ "$(dsh_proc_ns "$_pid" 2>/dev/null)" = "$_want" ] && printf '%s\n' "$_pid"
  done
}
