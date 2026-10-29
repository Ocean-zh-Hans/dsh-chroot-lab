#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Build a module zip. Never installs or runs it.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
if [ -d "$ROOT/build/.tools/bin" ]; then
  PATH="$ROOT/build/.tools/bin:$ROOT/build/.tools/msys/usr/bin:$PATH"
fi

SKIP_CHECK=0
WITH_CONSOLE=0
for arg in "$@"; do
  case "$arg" in
  --skip-check) SKIP_CHECK=1 ;;
  --with-console) WITH_CONSOLE=1 ;;
  -h | --help)
    printf 'Usage: %s [--skip-check] [--with-console]\n' "$0"
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
"$ROOT/build/build-tools.sh" --verify

version=$(awk -F= '$1 == "version" { print $2; exit }' module/module.prop)
[ -n "$version" ] || {
  printf 'missing version in module.prop\n' >&2
  exit 2
}
out_rel="dist/dsh_chroot-${version}.zip"
out="$ROOT/$out_rel"
mkdir -p "$ROOT/dist"
rm -f "$out"

tmp=$(mktemp -d "$ROOT/dist/.module.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cp -a module/. "$tmp/module/"
rm -rf "$tmp/module/tools/src"
for script in customize.sh service.sh boot-completed.sh uninstall.sh action.sh \
  bin/dsh-ctl bin/dsh-nsinit bin/dsh-watchdog bin/dsh-recover-mounts bin/dsh-log-limit bin/dsh-lock \
  control/bin/dsh-run control/bin/dsh-healthcheck.py control/s6/dsh/run control/s6/dsh/finish control/s6/ttyd/run \
  control/hooks/riskcontext.sh control/hooks/posttool.sh tools/bin/dsh-mount-ro-recursive \
  tools/bin/cp tools/libexec/cp tools/bin/debugfs tools/libexec/debugfs; do
  chmod 0755 "$tmp/module/$script"
done
if [ "$WITH_CONSOLE" -eq 1 ]; then
  console="$ROOT/console-app/dist/chroot-console-0.2.0.apk"
  [ -f "$console" ] || {
    printf 'console APK not built: %s\n' "$console" >&2
    exit 2
  }
  cp "$console" "$tmp/module/console.apk"
fi

(
  cd "$tmp/module"
  zip -r -X "../../$(basename "$out")" . -x '*.pyc' -x '*/__pycache__/*' >/dev/null
)
if [ "$WITH_CONSOLE" -eq 1 ]; then
  python3 build/verify-module.py "$out_rel" --console --fix-modes
else
  python3 build/verify-module.py "$out_rel" --fix-modes
fi

printf 'built: %s\n' "$out"
printf 'console: %s\n' "$([ "$WITH_CONSOLE" -eq 1 ] && printf 'optional APK only; no system overlay' || printf 'not included')"
unzip -l "$out_rel" | sed -n '1,12p'
