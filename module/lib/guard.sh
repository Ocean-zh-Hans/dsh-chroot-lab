# shellcheck shell=sh disable=SC3043
# Guard selects a permission preset for new sessions; audit/risk context is independent.

dsh_guard_status() {
  _v=$(dsh_read_line "$DSH_GUARD_FILE" 2>/dev/null || true)
  case $_v in
  1) printf 'guard: on for new sessions (approval-guard; audit/risk context)\n' ;;
  *) printf 'guard: off for new sessions (approval-free; audit/risk context)\n' ;;
  esac
}

dsh_guard() {
  _op=${1:-status}
  case $_op in
  status) dsh_guard_status ;;
  on)
    dsh_state_ensure || return 1
    dsh_state_write_atomic "$DSH_GUARD_FILE" 1 || return 1
    printf 'guard: on for new sessions; restart DSH/chroot to apply\n'
    printf 'existing sessions: select /permission approval-guard\n'
    ;;
  off)
    dsh_state_ensure || return 1
    dsh_state_write_atomic "$DSH_GUARD_FILE" 0 || return 1
    printf 'guard: off for new sessions; restart DSH/chroot to apply\n'
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
