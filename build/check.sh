#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Static checks only. This script never touches /data, mounts, loops, or SELinux.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'missing required tool: %s\n' "$1" >&2
    exit 2
  }
}
need shellcheck
need shfmt
need sh
need mksh
need zip
need unzip

mapfile -t scripts < <(
  find module bake agent-app -type f |
    while IFS= read -r f; do
      case "$f" in
        *.sh | module/bin/* | module/control/bin/* | module/control/s6/*/run | module/control/s6/*/finish) printf '%s\n' "$f" ;;
      esac
    done | sort
)

if [ "${#scripts[@]}" -eq 0 ]; then
  printf 'no scripts found\n' >&2
  exit 2
fi

printf '== sh -n ==\n'
for f in "${scripts[@]}"; do
  sh -n "$f"
done

printf '== mksh -n ==\n'
for f in "${scripts[@]}"; do
  mksh -n "$f"
done

printf '== shellcheck ==\n'
shellcheck -s sh -e SC3043,SC1091 "${scripts[@]}"

printf '== shfmt ==\n'
_shfmt_diff=$(mktemp "${TMPDIR:-/tmp}/dsh-shfmt.XXXXXX")
if ! shfmt -d -ln=mksh -i 2 "${scripts[@]}" >"$_shfmt_diff" 2>&1; then
  cat "$_shfmt_diff" >&2
  rm -f "$_shfmt_diff"
  exit 1
fi
rm -f "$_shfmt_diff"

printf '== C helper ==\n'
if command -v cc >/dev/null 2>&1; then
  cc -O2 -Wall -Wextra -fsyntax-only module/tools/src/dsh-mount-ro-recursive.c
else
  printf 'cc not found; skipped C syntax check\n'
fi

printf '== module layout ==\n'
for f in module.prop customize.sh service.sh boot-completed.sh uninstall.sh action.sh \
  bin/dsh-ctl bin/dsh-nsinit bin/dsh-watchdog control/bin/dsh-run \
  lib/common.sh lib/config.sh lib/log.sh lib/proc.sh lib/doctor.sh lib/plan.sh lib/status.sh; do
  [ -f "module/$f" ] || {
    printf 'missing module file: %s\n' "$f" >&2
    exit 2
  }
done
[ ! -e module/install.sh ] || {
  printf 'module/install.sh must not exist\n' >&2
  exit 2
}

printf '== docs ==\n'
python3 build/check-docs.py

printf '== tests ==\n'
bash tests/run.sh

printf '\nall static checks passed\n'
