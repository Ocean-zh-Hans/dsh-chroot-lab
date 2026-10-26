# shellcheck shell=sh disable=SC3043
# Guard state is read by /opt/dsh-control/hooks/pretool.sh on every call.

dsh_guard_status() {
  _v=$(dsh_read_line "$DSH_GUEST_DIR/guard" 2>/dev/null || true)
  case $_v in 1) printf 'guard: on (deny/ask active; hardguard always on)\n' ;; *) printf 'guard: off (hardguard only)\n' ;; esac
}

dsh_guard() {
  _op=${1:-status}
  case $_op in
  status) dsh_guard_status ;;
  on)
    dsh_state_ensure || return 1
    printf '1\n' >"$DSH_GUEST_DIR/guard" || return 1
    printf 'guard: on (deny/ask active; hardguard always on)\n'
    ;;
  off)
    dsh_state_ensure || return 1
    printf '0\n' >"$DSH_GUEST_DIR/guard" || return 1
    printf 'guard: off (hardguard only)\n'
    ;;
  -h | --help)
    printf 'Usage: dsh-ctl guard status|on|off\n'
    ;;
  *)
    dsh_err "unknown guard operation: $_op"
    return 2
    ;;
  esac
}
