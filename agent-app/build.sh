#!/bin/sh
# Build a minimal unsigned/debug-signed agent APK with the local Android SDK.
# This app is optional; it is not installed by the KSU module.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=${BUILD_TOOLS:-$SDK/build-tools/35.0.0}
JAR=${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}
OUT=$ROOT/build
APK=$ROOT/dist/dsh-agent-0.1.0.apk
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$ROOT/dist"

AAPT2=$(command -v aapt2 2>/dev/null || printf '%s' "$BT/aapt2")
D8=$(command -v d8 2>/dev/null || printf '%s' "$BT/d8")
APKSIGNER=$(command -v apksigner 2>/dev/null || printf '%s' "$BT/apksigner")
ZIPALIGN=$(command -v zipalign 2>/dev/null || true)
[ -f "$JAR" ] || {
  echo "missing $JAR" >&2
  exit 1
}

"$AAPT2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 --target-sdk-version 35 \
  "$OUT/res.zip"

find "$ROOT/src" "$OUT/gen" -name '*.java' -print >"$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -cp "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' -print >"$OUT/classes.txt"
"$D8" --lib "$JAR" --min-api 29 --output "$OUT/dex" @"$OUT/classes.txt"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

KS=$ROOT/debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=DSH Agent Debug,O=DSH,C=CN" >/dev/null 2>&1
fi

if [ -n "$ZIPALIGN" ]; then
  "$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
else
  cp "$OUT/unsigned.apk" "$OUT/aligned.apk"
  echo "zipalign unavailable; APK is not zipaligned (install may warn)"
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/aligned.apk"
"$APKSIGNER" verify "$APK"
echo "built: $APK"
