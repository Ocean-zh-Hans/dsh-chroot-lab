#!/bin/sh
# Build the tiny recursive-ro helper. Binaries are intentionally gitignored.
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/module/tools/bin"
mkdir -p "$OUT"
CC=${CC:-cc}
SRC="$ROOT/module/tools/src/dsh-mount-ro-recursive.c"
if "$CC" -O2 -static -o "$OUT/dsh-mount-ro-recursive" "$SRC" 2>/dev/null; then
  echo "built static: $OUT/dsh-mount-ro-recursive"
else
  "$CC" -O2 -o "$OUT/dsh-mount-ro-recursive" "$SRC"
  echo "built dynamic (verify against Android bionic): $OUT/dsh-mount-ro-recursive"
fi
file "$OUT/dsh-mount-ro-recursive" 2>/dev/null || true
