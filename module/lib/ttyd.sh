# shellcheck shell=sh disable=SC3043
# Optional authenticated ttyd control. The service remains disabled by default.

dsh_ttyd_new_password() {
  _a=$(cat "$DSH_PROC/sys/kernel/random/uuid" 2>/dev/null) || return 1
  _b=$(cat "$DSH_PROC/sys/kernel/random/uuid" 2>/dev/null) || return 1
  printf '%s\n' "$_a$_b" | tr -d '-'
}

dsh_ttyd_credentials() {
  _pass=$(dsh_read_line "$DSH_TTYD_PASS_FILE" 2>/dev/null || true)
  case $_pass in
  *[!A-Za-z0-9]* | '') _pass= ;;
  esac
  if [ "${#_pass}" -lt 32 ] || [ "${#_pass}" -gt 128 ]; then
    _pass=$(dsh_ttyd_new_password) || {
      dsh_err "cannot generate ttyd credentials"
      return 1
    }
  fi
  case $_pass in
  *[!A-Za-z0-9]*)
    dsh_err "ttyd credential generation returned invalid data"
    return 1
    ;;
  esac
  if [ "${#_pass}" -lt 32 ] || [ "${#_pass}" -gt 128 ]; then
    dsh_err "ttyd credential generation returned an invalid length"
    return 1
  fi
  dsh_state_write_atomic "$DSH_TTYD_PASS_FILE" "$_pass" || return 1
  chmod 0600 "$DSH_TTYD_PASS_FILE" || return 1
  return 0
}

dsh_ttyd_holder() {
  local _pid="$1" _st _ns
  _st=$(dsh_read_line "$DSH_STARTTIME_FILE" 2>/dev/null || true)
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  dsh_is_uint "$_pid" && dsh_proc_validate_identity "$_pid" "$_st" "$_ns"
}

dsh_ttyd_on() {
  local _pid
  dsh_ttyd_credentials || return 1
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if dsh_ttyd_holder "$_pid"; then
    dsh_guest_run "$_pid" /bin/rm -f /run/s6/ttyd/down || return 1
    dsh_guest_run "$_pid" /usr/bin/s6-svscanctl -a /run/s6 || return 1
    dsh_guest_run "$_pid" /usr/bin/s6-svc -u /run/s6/ttyd || return 1
  fi
  rm -f "$DSH_TTYD_DOWN_FILE" || return 1
  printf 'ttyd: enabled with authentication on 127.0.0.1:7681\n'
  printf 'credentials: user dsh, password file %s (0600)\n' "$DSH_TTYD_PASS_FILE"
}

dsh_ttyd_off() {
  local _pid _rc=0
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if dsh_ttyd_holder "$_pid"; then
    dsh_guest_run "$_pid" /usr/bin/s6-svc -d /run/s6/ttyd || _rc=1
    dsh_guest_run "$_pid" /usr/bin/s6-svwait -d -t 10000 /run/s6/ttyd || _rc=1
    dsh_guest_run "$_pid" /usr/bin/touch /run/s6/ttyd/down || _rc=1
  fi
  dsh_state_write_atomic "$DSH_TTYD_DOWN_FILE" '' || _rc=1
  if [ "$_rc" -ne 0 ]; then
    dsh_err "ttyd may still be running; check status and stop the holder if needed"
    return 1
  fi
  printf 'ttyd: disabled\n'
}

dsh_ttyd_status() {
  local _pid
  if [ -f "$DSH_TTYD_DOWN_FILE" ]; then
    printf 'ttyd: configured off\n'
  else
    printf 'ttyd: configured on\n'
  fi
  if [ -s "$DSH_TTYD_PASS_FILE" ]; then
    printf 'credentials: present (password is not displayed)\n'
  else
    printf 'credentials: missing\n'
  fi
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if [ ! -f "$DSH_TTYD_DOWN_FILE" ] && dsh_ttyd_holder "$_pid"; then
    dsh_guest_run "$_pid" /usr/bin/s6-svstat /run/s6/ttyd || true
  fi
  return 0
}

dsh_ttyd() {
  case ${1:-status} in
  status) dsh_ttyd_status ;;
  on) dsh_ttyd_on ;;
  off) dsh_ttyd_off ;;
  -h | --help) printf 'Usage: dsh-ctl ttyd status|on|off\n' ;;
  *)
    dsh_err "unknown ttyd operation: ${1:-}"
    return 2
    ;;
  esac
}
