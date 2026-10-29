#!/bin/sh
# DSH PostToolUse audit hook: record tool metadata, not credentials/tool input.
set -u
_input=$(cat 2>/dev/null || true)
_dir=${DSH_AUDIT_DIR:-/var/log/dsh/audit}
mkdir -p "$_dir" 2>/dev/null || true
_tool=$(printf '%s' "$_input" | jq -r '.tool_name // "unknown"' 2>/dev/null || printf unknown)
_log=$_dir/posttool-$(date '+%Y-%m-%d' 2>/dev/null || date '+%s').log
find "$_dir" -maxdepth 1 -type f -name 'posttool-????-??-??.log' -mtime +14 -delete 2>/dev/null || true
printf '%s\t%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" "$_tool" >>"$_log" 2>/dev/null || true
exit 0
