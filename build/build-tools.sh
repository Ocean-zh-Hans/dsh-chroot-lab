#!/bin/sh
# Build and verify the Android ARM64 mount helper.
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/module/tools/bin"
SRC="$ROOT/module/tools/src/dsh-mount-ro-recursive.c"
MODE=${1:-build}
HOST_CYGPATH=
if [ -x /usr/bin/cygpath ]; then
  HOST_CYGPATH=/usr/bin/cygpath
else
  HOST_CYGPATH=$(command -v cygpath 2>/dev/null || true)
fi

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ]; then
  for _candidate in "$HOME/AppData/Local/Android/Sdk" "$HOME/android-sdk"; do
    if [ -d "$_candidate/ndk" ]; then
      SDK=$_candidate
      break
    fi
  done
fi
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
NDK_VERSION=${DSH_NDK_VERSION:-28.2.13676358}
if [ -z "$NDK" ] && [ -n "$SDK" ]; then
  NDK=$SDK/ndk/$NDK_VERSION
fi
[ -d "$NDK" ] || {
  echo "Android NDK not found; set ANDROID_NDK_HOME or DSH_NDK_VERSION" >&2
  exit 2
}
case "$(uname -s):$(uname -m)" in
MINGW*:* | MSYS*:* | CYGWIN*:*) HOST_TAG=windows-x86_64; CC_SUFFIX=.cmd ;;
Linux:aarch64 | Linux:arm64) HOST_TAG=linux-aarch64; CC_SUFFIX= ;;
Linux:*) HOST_TAG=linux-x86_64; CC_SUFFIX= ;;
Darwin:*) HOST_TAG=darwin-x86_64; CC_SUFFIX= ;;
*)
  echo "unsupported build host" >&2
  exit 2
  ;;
esac
CC=${CC:-$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android24-clang$CC_SUFFIX}
[ -f "$CC" ] || {
  echo "Android ARM64 clang not found: $CC" >&2
  exit 2
}
if [ "$MODE" = --print-cc ]; then
  printf '%s\n' "$CC"
  exit 0
fi

validate() {
  _binary=$1
  _info=$(file "$_binary") || return 1
  printf '%s\n' "$_info" | grep -q 'ELF 64-bit LSB.*ARM aarch64' || {
    echo "not an ARM64 ELF: $_binary" >&2
    return 1
  }
  if ! printf '%s\n' "$_info" | grep -q '/system/bin/linker64' &&
    ! printf '%s\n' "$_info" | grep -q 'statically linked'; then
    echo "binary is not Android-bionic or statically linked: $_info" >&2
    return 1
  fi
  return 0
}

READELF=${READELF:-$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-readelf}
[ -f "$READELF" ] || READELF=$READELF.exe
[ -f "$READELF" ] || {
  echo "Android readelf not found: $READELF" >&2
  exit 2
}
if [ "$MODE" = --verify ]; then
  validate "$OUT/dsh-mount-ro-recursive"
  python3 "$ROOT/build/fetch-android-tools.py" --verify \
    --lock "$ROOT/build/android-tools.json" \
    --output "$ROOT/module/tools" \
    --downloads "$ROOT/build/.tools/android-tools-download" \
    --readelf "$READELF"
  (cd "$ROOT/module/tools" && sha256sum -c SHA256SUMS)
  exit 0
fi
[ "$MODE" = build ] || {
  echo "usage: $0 [build|--verify|--print-cc]" >&2
  exit 2
}

mkdir -p "$OUT"
_tmp=$OUT/dsh-mount-ro-recursive.tmp.$$
trap 'rm -f "$_tmp"' EXIT HUP INT TERM
if [ -n "$HOST_CYGPATH" ]; then
  _src_native=$("$HOST_CYGPATH" -w "$SRC")
  _tmp_native=$("$HOST_CYGPATH" -w "$_tmp")
else
  _src_native=$SRC
  _tmp_native=$_tmp
fi
"$CC" -O2 -Wall -Wextra -Werror -fPIE -pie -o "$_tmp_native" "$_src_native"
validate "$_tmp"
chmod 0755 "$_tmp"
mv -f "$_tmp" "$OUT/dsh-mount-ro-recursive"
python3 "$ROOT/build/fetch-android-tools.py" \
  --lock "$ROOT/build/android-tools.json" \
  --output "$ROOT/module/tools" \
  --downloads "$ROOT/build/.tools/android-tools-download" \
  --readelf "$READELF"
if [ -r "$NDK/source.properties" ]; then
  NDK_VERSION=$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties" | head -n 1)
fi
{
  printf 'ndk=%s\n' "$NDK_VERSION"
  printf 'abi=arm64\napi=24\n'
} >"$OUT/BUILDINFO"
trap - EXIT HUP INT TERM
printf 'built Android ARM64 helper: %s\n' "$OUT/dsh-mount-ro-recursive"
file "$OUT/dsh-mount-ro-recursive"
