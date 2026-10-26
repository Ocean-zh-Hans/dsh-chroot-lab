#!/bin/sh
# P1 placeholder hook. Not wired in hooks.json yet.
# Keep exit 0 (allow) but log the raw event for later parser work.

set -u
_input=$(cat 2>/dev/null || true)
_dir=/var/log/dsh/audit
mkdir -p "$_dir" 2>/dev/null || true
printf '%s pretool %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date)" "$_input" >>"$_dir/pretool.log" 2>/dev/null || true
exit 0
