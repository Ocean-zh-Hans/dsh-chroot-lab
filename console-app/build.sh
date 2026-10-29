#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
if [ ! -f "$ROOT/console-app/debug.keystore" ]; then
  keytool -genkeypair -v -keystore "$ROOT/console-app/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=DSH Debug,O=DSH,C=CN" >/dev/null 2>&1
fi
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ]; then
  for candidate in "$HOME/AppData/Local/Android/Sdk" "$HOME/android-sdk"; do
    if [ -d "$candidate/platforms" ]; then
      SDK=$candidate
      break
    fi
  done
fi
[ -n "$SDK" ] || {
  printf 'Android SDK not found; set ANDROID_HOME\n' >&2
  exit 2
}
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
_host_path=
_old_ifs=$IFS
IFS=:
for _dir in $PATH; do
  case $_dir in
  */build/.tools/bin | */build/.tools/msys/usr/bin) ;;
  *) _host_path="$_host_path${_host_path:+:}$_dir" ;;
  esac
done
IFS=$_old_ifs
PATH=$_host_path
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME" ]; then
  JAVA_HOME=$(CDPATH='' cd -- "$JAVA_HOME" && pwd)
  export JAVA_HOME
fi
./gradlew :console-app:assembleDebug --write-verification-metadata sha256 --no-daemon --console=plain
./gradlew :console-app:dependencies --write-locks --no-daemon --console=plain
./gradlew :console-app:assembleDebug --write-verification-metadata sha256 --no-daemon --console=plain
APK=$ROOT/console-app/dist/chroot-console-0.2.0.apk
mkdir -p "$ROOT/console-app/dist"
cp "$ROOT/console-app/build/outputs/apk/debug/console-app-debug.apk" "$APK"
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
[ -f "$BT/lib/apksigner.jar" ] || {
  printf 'apksigner.jar not found under %s\n' "$SDK" >&2
  exit 2
}
java -jar "$BT/lib/apksigner.jar" verify "$APK"
if [ -x "$BT/zipalign" ]; then
  "$ROOT/build/zipalign.sh" "$BT" -c 4 "$APK"
elif [ -f "$BT/zipalign.exe" ]; then
  "$BT/zipalign.exe" -c 4 "$APK"
else
  "$BT/zipalign.bat" -c 4 "$APK"
fi
printf 'built: %s\n' "$APK"
