#!/bin/sh
# DSH PreToolUse hook. Hardguard is always on; deny/ask lists are opt-in.
# This is a command-path guardrail, not a security boundary.

set -u
_input=$(cat 2>/dev/null || true)
_dir=${DSH_HOOK_DIR:-/opt/dsh-control/hooks}
_guard=${DSH_HOOK_STATE:-/run/dsh/guard}
_audit=${DSH_AUDIT_DIR:-/var/log/dsh/audit}
_tmpdir=${DSH_HOOK_TMP:-${TMPDIR:-/tmp}}
mkdir -p "$_audit" 2>/dev/null || true
printf '%s pretool %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" "$_input" >>"$_audit/pretool.log" 2>/dev/null || true

# Built-in hardguard: never allow the obvious global-hiddenness switches.
case $_input in
*setenforce*0* | *resetprop* | *'/sys/fs/selinux'* | *'mount --make-rshared'* | *'mount --make-shared'*)
  printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"hardguard: global SELinux/prop/mount propagation is blocked"}}'
  exit 0
  ;;
esac

# Optional deny/ask lists. They are read fresh on every call.
_guard_on=0
[ -r "$_guard" ] && [ "$(cat "$_guard" 2>/dev/null)" = 1 ] && _guard_on=1
if [ "$_guard_on" = 1 ]; then
  if [ -r "$_dir/deny.txt" ] && [ -s "$_dir/deny.txt" ]; then
    _patterns=$(mktemp "$_tmpdir/dsh-deny.XXXXXX" 2>/dev/null || true)
    if [ -n "$_patterns" ]; then
      grep -Ev '^[[:space:]]*(#|$)' "$_dir/deny.txt" >"$_patterns" 2>/dev/null || true
      if [ -s "$_patterns" ] && printf '%s' "$_input" | grep -Eqf "$_patterns"; then
        rm -f "$_patterns"
        printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"guard: deny.txt match"}}'
        exit 0
      fi
      rm -f "$_patterns"
    fi
  fi
  if [ -r "$_dir/ask.txt" ] && [ -s "$_dir/ask.txt" ]; then
    _patterns=$(mktemp "$_tmpdir/dsh-ask.XXXXXX" 2>/dev/null || true)
    if [ -n "$_patterns" ]; then
      grep -Ev '^[[:space:]]*(#|$)' "$_dir/ask.txt" >"$_patterns" 2>/dev/null || true
      if [ -s "$_patterns" ] && printf '%s' "$_input" | grep -Eqf "$_patterns"; then
        rm -f "$_patterns"
        printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"guard: ask.txt match"}}'
        exit 0
      fi
      rm -f "$_patterns"
    fi
  fi
fi

exit 0
