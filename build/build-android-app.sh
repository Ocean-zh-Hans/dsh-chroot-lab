#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [ -x /usr/bin/cygpath ]; then
  HOST_CYGPATH=/usr/bin/cygpath
else
  HOST_CYGPATH=$(command -v cygpath 2>/dev/null || true)
fi
APP_DIR=$1
APK_NAME=$2
[ -d "$APP_DIR/src" ] || {
  printf 'invalid Android app directory: %s\n' "$APP_DIR" >&2
  exit 2
}
if [ -d "$ROOT/build/.tools/bin" ]; then
  PATH="$ROOT/build/.tools/bin:$ROOT/build/.tools/msys/usr/bin:$PATH"
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
if [ -n "${BUILD_TOOLS:-}" ]; then
  BT=$BUILD_TOOLS
else
  BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi
if [ -n "${ANDROID_JAR:-}" ]; then
  JAR=$ANDROID_JAR
else
  JAR=$(find "$SDK/platforms" -name android.jar -type f | sort -V | tail -n 1)
fi
[ -f "$JAR" ] || {
  printf 'android.jar not found under %s\n' "$SDK" >&2
  exit 2
}

pick_tool() {
  name=$1
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
  elif [ -f "$BT/$name.exe" ]; then
    printf '%s\n' "$BT/$name.exe"
  elif [ -f "$BT/$name.bat" ]; then
    printf '%s\n' "$BT/$name.bat"
  elif [ -f "$BT/$name" ]; then
    printf '%s\n' "$BT/$name"
  else
    return 1
  fi
}
AAPT2=$(pick_tool aapt2)
D8_JAR=$BT/lib/d8.jar
APKSIGNER_JAR=$BT/lib/apksigner.jar
ZIPALIGN=$(pick_tool zipalign)
[ -f "$D8_JAR" ] && [ -f "$APKSIGNER_JAR" ] || {
  printf 'SDK build-tools are missing d8.jar or apksigner.jar: %s\n' "$BT" >&2
  exit 2
}

native() {
  if [ -n "$HOST_CYGPATH" ]; then
    "$HOST_CYGPATH" -w "$1"
  else
    printf '%s\n' "$1"
  fi
}
run_jar() {
  jar=$1
  main=$2
  shift 2
  java -cp "$(native "$jar")" "$main" "$@"
}

OUT=$APP_DIR/build
APK=$APP_DIR/dist/$APK_NAME
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$APP_DIR/dist"
"$AAPT2" compile --dir "$(native "$APP_DIR/res")" -o "$(native "$OUT/res.zip")"
"$AAPT2" link -o "$(native "$OUT/base.apk")" -I "$(native "$JAR")" \
  --manifest "$(native "$APP_DIR/AndroidManifest.xml")" \
  --java "$(native "$OUT/gen")" --min-sdk-version 29 --target-sdk-version 35 \
  "$(native "$OUT/res.zip")"

: >"$OUT/sources.txt"
while IFS= read -r -d '' source; do
  native "$source" >>"$OUT/sources.txt"
done < <(find "$APP_DIR/src" "$OUT/gen" -name '*.java' -print0)
javac -source 8 -target 8 -Xlint:-options -cp "$(native "$JAR")" \
  -d "$(native "$OUT/classes")" "@$(native "$OUT/sources.txt")"

: >"$OUT/classes.txt"
while IFS= read -r -d '' class; do
  native "$class" >>"$OUT/classes.txt"
done < <(find "$OUT/classes" -name '*.class' -print0)
run_jar "$D8_JAR" com.android.tools.r8.D8 --lib "$(native "$JAR")" --min-api 29 \
  --output "$(native "$OUT/dex")" "@$(native "$OUT/classes.txt")"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
KS=$APP_DIR/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$(native "$KS")" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=DSH Debug,O=DSH,C=CN" >/dev/null 2>&1
fi
"$ZIPALIGN" -f 4 "$(native "$OUT/unsigned.apk")" "$(native "$OUT/aligned.apk")"
run_jar "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$(native "$KS")" --ks-pass pass:android \
  --key-pass pass:android --out "$(native "$APK")" "$(native "$OUT/aligned.apk")"
run_jar "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool verify "$(native "$APK")"
"$ZIPALIGN" -c 4 "$(native "$APK")"
unzip -Z1 "$APK" | grep -qx 'classes.dex' || {
  printf 'APK is missing root classes.dex: %s\n' "$APK" >&2
  exit 1
}
printf 'built: %s\n' "$APK"
