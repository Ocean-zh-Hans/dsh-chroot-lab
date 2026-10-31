# shellcheck shell=sh disable=SC3043
# Risk classification and confirmation gate. This is a speed bump for a
# good-but-careless operator, not a security boundary against a malicious root.

dsh_risk_level() {
  _risk_op=${1:-}
  _risk_sub=${2:-}
  case $_risk_op:$_risk_sub in
  stop:* | restart:* | purge:* | recover:* | rescue:* | start:* | restore:*) printf 'R3\n' ;;
  image: | image:create | image:install | image:replace) printf 'R3\n' ;;
  snapshot: | snapshot:create | snapshot:restore | snapshot:promote | snapshot:restore-last-good) printf 'R3\n' ;;
  system-rw:on | system-rw:off) printf 'R3\n' ;;
  autostart:on | autostart:off) printf 'R3\n' ;;
  guard:on | guard:off) printf 'R3\n' ;;
  ttyd:on | ttyd:off) printf 'R3\n' ;;
  file:overwrite | sdcard:overwrite) printf 'R1\n' ;;
  data:write | app-data:write | data:delete) printf 'R2\n' ;;
  block:full | block:write | raw:write) printf 'R4\n' ;;
  fixperm:* | doctor:* | plan:* | status:* | config:* | log:* | logs:* | version:* | help:* | risk:* | boot:*) printf 'R0\n' ;;
  *) printf 'R0\n' ;;
  esac
}

dsh_risk_audit() {
  _risk_level=$1
  _risk_op=$2
  _risk_target=$3
  _risk_result=$4
  _risk_reason=${5:-}
  _risk_dir=${DSH_RISK_DIR:-$DSH_CTL_DIR/risk}
  mkdir -p "$_risk_dir" 2>/dev/null || return 0
  chmod 0700 "$_risk_dir" 2>/dev/null || true
  _risk_file=$_risk_dir/audit.log
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%s')" "$_risk_level" "$_risk_op" "$_risk_target" "$_risk_result" "$_risk_reason" >>"$_risk_file" 2>/dev/null || true
  chmod 0600 "$_risk_file" 2>/dev/null || true
}

dsh_risk_token_file() {
  _risk_op=$1
  _risk_dir=${DSH_RISK_DIR:-$DSH_CTL_DIR/risk}
  printf '%s/%s.token\n' "$_risk_dir" "$_risk_op"
}

dsh_risk_issue() {
  _risk_op=
  _risk_target=
  _risk_reason=
  _risk_impact=
  _risk_backup=
  _risk_level=R3
  while [ "$#" -gt 0 ]; do
    case $1 in
    --op)
      _risk_op=${2:-}
      shift 2
      ;;
    --target)
      _risk_target=${2:-}
      shift 2
      ;;
    --reason)
      _risk_reason=${2:-}
      shift 2
      ;;
    --impact)
      _risk_impact=${2:-}
      shift 2
      ;;
    --level)
      _risk_level=${2:-}
      shift 2
      ;;
    --backup)
      _risk_backup=${2:-}
      shift 2
      ;;
    *)
      dsh_err "unknown risk option: $1"
      return 2
      ;;
    esac
  done
  case $_risk_op in
  '' | *[!A-Za-z0-9_.-]*)
    dsh_err "risk issue requires a simple --op name"
    return 2
    ;;
  esac
  case $_risk_level in R2 | R3 | R4) ;; *)
    dsh_err "risk level must be R2, R3, or R4"
    return 2
    ;;
  esac
  [ "${#_risk_reason}" -ge 8 ] || {
    dsh_err "risk issue requires --reason of at least 8 characters"
    return 2
  }
  [ -n "$_risk_impact" ] || {
    dsh_err "risk issue requires --impact"
    return 2
  }
  if [ "$_risk_level" = R2 ] && [ -z "$_risk_backup" ]; then
    dsh_err "R2 risk issue requires --backup"
    return 2
  fi
  _risk_dir=${DSH_RISK_DIR:-$DSH_CTL_DIR/risk}
  mkdir -p "$_risk_dir" || return 1
  chmod 0700 "$_risk_dir" || return 1
  _risk_token=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n') || _risk_token=
  [ "${#_risk_token}" -ge 16 ] || {
    dsh_err "cannot obtain a random token"
    return 1
  }
  _risk_expiry=$(($(date '+%s') + ${DSH_RISK_TOKEN_TTL:-600}))
  _risk_file=$(dsh_risk_token_file "$_risk_op")
  _risk_tmp=$_risk_file.tmp.$$
  printf 'version=1\ntoken=%s\nop=%s\ntarget=%s\nlevel=%s\nexpiry=%s\nimpact=%s\nbackup=%s\n' \
    "$_risk_token" "$_risk_op" "$_risk_target" "$_risk_level" "$_risk_expiry" "$_risk_impact" "$_risk_backup" >"$_risk_tmp" || return 1
  chmod 0600 "$_risk_tmp" || {
    rm -f "$_risk_tmp"
    return 1
  }
  mv -f "$_risk_tmp" "$_risk_file" || {
    rm -f "$_risk_tmp"
    return 1
  }
  dsh_risk_audit "$_risk_level" "$_risk_op" "$_risk_target" issued "$_risk_reason"
  printf '%s\n' "$_risk_token"
}

dsh_risk_verify_token() {
  _risk_op=$1
  _risk_token=${2:-}
  [ -n "$_risk_token" ] || return 1
  _risk_file=$(dsh_risk_token_file "$_risk_op")
  [ -r "$_risk_file" ] || return 1
  _risk_tok=
  _risk_expiry=
  _risk_file_op=
  while IFS='=' read -r _risk_key _risk_val; do
    case $_risk_key in
    token) _risk_tok=$_risk_val ;;
    expiry) _risk_expiry=$_risk_val ;;
    op) _risk_file_op=$_risk_val ;;
    esac
  done <"$_risk_file"
  [ "$_risk_file_op" = "$_risk_op" ] || return 1
  [ "$_risk_tok" = "$_risk_token" ] || return 1
  dsh_is_uint "$_risk_expiry" || return 1
  [ "$_risk_expiry" -ge "$(date '+%s')" ] || return 1
  return 0
}

dsh_risk_gate() {
  _risk_op=${1:-}
  _risk_sub=${2:-}
  _risk_target=${3:-}
  if [ "${DSH_RISK_BYPASS:-0}" = 1 ]; then
    dsh_risk_audit "$(dsh_risk_level "$_risk_op" "$_risk_sub")" "$_risk_op" "$_risk_target" bypass "${DSH_RISK_REASON:-test-bypass}"
    return 0
  fi
  _risk_level=$(dsh_risk_level "$_risk_op" "$_risk_sub")
  case $_risk_level in
  R0)
    dsh_risk_audit R0 "$_risk_op" "$_risk_target" allow "${DSH_RISK_REASON:-}"
    return 0
    ;;
  R1)
    if [ "${DSH_RISK_CONFIRM:-}" = yes ]; then
      dsh_risk_audit R1 "$_risk_op" "$_risk_target" allow "${DSH_RISK_REASON:-}"
      return 0
    fi
    dsh_risk_audit R1 "$_risk_op" "$_risk_target" denied
    dsh_err "R1 requires DSH_RISK_CONFIRM=yes: $_risk_op"
    return 3
    ;;
  R2 | R3)
    _risk_reason=${DSH_RISK_REASON:-}
    if [ "$_risk_level" = R2 ]; then
      [ -n "${DSH_RISK_BACKUP:-}" ] && [ -e "${DSH_RISK_BACKUP:-}" ] || {
        dsh_risk_audit "$_risk_level" "$_risk_op" "$_risk_target" denied
        dsh_err "R2 requires DSH_RISK_BACKUP pointing to an existing backup"
        return 3
      }
    fi
    [ "${#_risk_reason}" -ge 8 ] || {
      dsh_risk_audit "$_risk_level" "$_risk_op" "$_risk_target" denied
      dsh_err "$_risk_level requires DSH_RISK_REASON (at least 8 characters)"
      return 3
    }
    if ! dsh_risk_verify_token "$_risk_op" "${DSH_RISK_TOKEN:-}"; then
      dsh_risk_audit "$_risk_level" "$_risk_op" "$_risk_target" denied
      dsh_err "$_risk_level requires a valid one-time token; run: dsh-ctl risk issue --op $_risk_op --reason ... --impact ..."
      return 3
    fi
    dsh_risk_audit "$_risk_level" "$_risk_op" "$_risk_target" allow "${DSH_RISK_REASON:-}"
    return 0
    ;;
  R4)
    _risk_reason=${DSH_RISK_REASON:-}
    [ "${#_risk_reason}" -ge 8 ] || {
      dsh_risk_audit R4 "$_risk_op" "$_risk_target" denied
      dsh_err "R4 requires a detailed DSH_RISK_REASON"
      return 3
    }
    if [ "${DSH_RISK_HUMAN:-}" != YES ] || [ ! -t 0 ]; then
      dsh_risk_audit R4 "$_risk_op" "$_risk_target" denied
      dsh_err "R4 requires a human TTY approval; AI must not self-approve"
      return 3
    fi
    dsh_risk_audit R4 "$_risk_op" "$_risk_target" allow "${DSH_RISK_REASON:-}"
    return 0
    ;;
  *) return 2 ;;
  esac
}

dsh_risk_cli() {
  _risk_sub=${1:-help}
  [ "$#" -gt 0 ] && shift
  case $_risk_sub in
  issue) dsh_risk_issue "$@" ;;
  check)
    _risk_op=
    _risk_sub=
    _risk_target=
    while [ "$#" -gt 0 ]; do
      case $1 in
      --op)
        _risk_op=${2:-}
        shift 2
        ;;
      --sub)
        _risk_sub=${2:-}
        shift 2
        ;;
      --target)
        _risk_target=${2:-}
        shift 2
        ;;
      --backup)
        DSH_RISK_BACKUP=${2:-}
        shift 2
        ;;
      *)
        dsh_err "unknown risk check option: $1"
        return 2
        ;;
      esac
    done
    dsh_risk_gate "$_risk_op" "$_risk_sub" "$_risk_target"
    ;;
  status)
    _risk_dir=${DSH_RISK_DIR:-$DSH_CTL_DIR/risk}
    [ -d "$_risk_dir" ] || {
      printf 'risk: no tokens\n'
      return 0
    }
    printf 'risk dir: %s\n' "$_risk_dir"
    ls -l "$_risk_dir" 2>/dev/null || true
    ;;
  help | -h | --help)
    printf '%s\n' 'Usage: dsh-ctl risk issue --op OP --target PATH --reason TEXT --impact TEXT [--level R2|R3|R4]'
    printf '%s\n' '       dsh-ctl risk check --op OP [--sub SUB] [--target PATH]'
    printf '%s\n' '       dsh-ctl risk status'
    ;;
  *)
    dsh_err "unknown risk operation: $_risk_sub"
    return 2
    ;;
  esac
}
