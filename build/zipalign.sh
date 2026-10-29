#!/bin/bash
set -euo pipefail

if [ "$#" -lt 1 ]; then
  printf 'usage: %s <build-tools-dir> <zipalign args...>\n' "$0" >&2
  exit 2
fi
BT=$1
shift

QEMU_X86_64=${QEMU_X86_64:-/usr/bin/qemu-x86_64-static}
QEMU_X86_LIBS=${QEMU_X86_LIBS:-/opt/x86-libs}

if [ -x "$BT/zipalign" ]; then
  if [ "$(uname -m)" = aarch64 ]; then
    FORMAT=$(file -b "$BT/zipalign" 2>/dev/null || true)
    case "$FORMAT" in
    *x86-64*)
      if [ ! -x "$QEMU_X86_64" ]; then
        printf 'zipalign is x86-64; qemu-x86_64-static not found at %s\n' "$QEMU_X86_64" >&2
        exit 2
      fi
      if [ ! -d "$QEMU_X86_LIBS" ]; then
        printf 'zipalign is x86-64; x86 library root not found at %s\n' "$QEMU_X86_LIBS" >&2
        exit 2
      fi
      exec "$QEMU_X86_64" -L "$QEMU_X86_LIBS" "$BT/zipalign" "$@"
      ;;
    esac
  fi
  exec "$BT/zipalign" "$@"
fi
if [ -f "$BT/zipalign.exe" ]; then
  exec "$BT/zipalign.exe" "$@"
fi
if [ -f "$BT/zipalign.bat" ]; then
  exec "$BT/zipalign.bat" "$@"
fi
printf 'zipalign not found under %s\n' "$BT" >&2
exit 2
