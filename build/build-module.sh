#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Build a skeleton module zip. Never installs or runs it.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

SKIP_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --skip-check) SKIP_CHECK=1 ;;
    -h | --help)
      printf 'Usage: %s [--skip-check]\n' "$0"
      exit 0
      ;;
    *)
      printf 'unknown option: %s\n' "$arg" >&2
      exit 2
      ;;
  esac
done

if [ "$SKIP_CHECK" -eq 0 ]; then
  "$ROOT/build/check.sh"
fi

version=$(awk -F= '$1 == "version" { print $2; exit }' module/module.prop)
[ -n "$version" ] || {
  printf 'missing version in module.prop\n' >&2
  exit 2
}

out="$ROOT/dist/dsh_chroot-${version}.zip"
mkdir -p "$ROOT/dist"
rm -f "$out"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/dsh-module.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cp -a module/. "$tmp/module/"
rm -rf "$tmp/module/tools/src"
if [ -f "$ROOT/console-app/dist/dsh-console-0.1.0.apk" ]; then
  cp "$ROOT/console-app/dist/dsh-console-0.1.0.apk" "$tmp/module/console.apk"
fi

(
  cd "$tmp/module"
  zip -r -X "$out" . -x '*.pyc' -x '*/__pycache__/*' >/dev/null
)

if unzip -l "$out" | awk '{print $4}' | grep -qx 'install.sh'; then
  printf 'refusing to build: install.sh found in zip root\n' >&2
  exit 2
fi

printf 'built: %s\n' "$out"
unzip -l "$out" | sed -n '1,12p'
