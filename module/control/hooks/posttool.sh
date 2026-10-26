#!/bin/sh
# DSH PostToolUse audit hook: append one compact JSONL-ish line.
set -u
_input=$(cat 2>/dev/null || true)
_dir=${DSH_AUDIT_DIR:-/var/log/dsh/audit}
mkdir -p "$_dir" 2>/dev/null || true
printf '%s\t%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" "$_input" >>"$_dir/posttool.log" 2>/dev/null || true
exit 0
