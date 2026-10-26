#!/bin/sh
# P1 placeholder hook. Not wired in hooks.json yet.
# Append a compact audit line; do not block.

set -u
_input=$(cat 2>/dev/null || true)
_dir=/var/log/dsh/audit
mkdir -p "$_dir" 2>/dev/null || true
printf '%s posttool %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" "$_input" >>"$_dir/posttool.log" 2>/dev/null || true
exit 0
