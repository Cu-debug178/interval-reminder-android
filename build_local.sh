#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${ANDROID_LOCAL_TOOLS:-/home/cudebug/Android/local-tools}"
STUDIO="${ANDROID_STUDIO_HOME:-/home/cudebug/.local/share/JetBrains/Toolbox/apps/android-studio}"
SDK="$TOOLS/usr/lib/android-sdk"
JBR="$STUDIO/jbr/bin"
BUILD="${BUILD_DIR:-$ROOT/build}"
MANIFEST="${MANIFEST_FILE:-$ROOT/app/src/main/AndroidManifest.xml}"
CUSTOM_PACKAGE=()
if [[ -n "${TEST_JAVA_DIR:-}" ]]; then CUSTOM_PACKAGE=(--custom-package com.example.reviewreminder); fi

mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/generated"
rm -rf "$BUILD/classes"/* "$BUILD/dex"/* "$BUILD/generated"/* "$BUILD/unsigned.apk" "$BUILD/interval-reminder-debug.apk"

# Local debug APKs are debuggable so emulator/device QA can inject settings via run-as.
# Do not use this for a user-facing release APK.
DEBUG_MODE_FLAG=()
if [[ "${RELEASE_BUILD:-0}" != "1" ]]; then DEBUG_MODE_FLAG=(--debug-mode); fi

LD_LIBRARY_PATH="$TOOLS/usr/lib/x86_64-linux-gnu/android" \
  "$TOOLS/usr/bin/aapt" package -m -f \
  -J "$BUILD/generated" "${CUSTOM_PACKAGE[@]}" \
  -M "$MANIFEST" \
  -S "$ROOT/app/src/main/res" \
  -A "$ROOT/app/src/main/assets" \
  -I "$SDK/platforms/android-23/android.jar" \
  "${DEBUG_MODE_FLAG[@]}" \
  -F "$BUILD/unsigned.apk"

mapfile -t SOURCES < <(find "$ROOT/app/src/main/java" "$BUILD/generated" ${TEST_JAVA_DIR:+"$TEST_JAVA_DIR"} -name '*.java')

"$JBR/javac" --release 8 -cp "$SDK/platforms/android-23/android.jar" \
  -d "$BUILD/classes" "${SOURCES[@]}"
"$JBR/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$JBR/java" -cp "$STUDIO/plugins/android/lib/r8.jar" com.android.tools.r8.D8 \
  --min-api 23 --lib "$SDK/platforms/android-23/android.jar" \
  --output "$BUILD/dex" "$BUILD/classes.jar"

(cd "$BUILD/dex" && zip -q -u "$BUILD/unsigned.apk" classes.dex)

if [[ ! -f "$ROOT/build/release.keystore" ]]; then
  "$JBR/keytool" -genkeypair -keystore "$ROOT/build/release.keystore" \
    -storepass android -keypass android -alias reviewreminder \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=Interval Reminder,O=Local'
fi
"$JBR/java" -jar "$TOOLS/usr/lib/android-sdk/build-tools/debian/apksigner.jar" sign \
  --ks "$ROOT/build/release.keystore" --ks-pass pass:android \
  --out "$BUILD/interval-reminder-debug.apk" "$BUILD/unsigned.apk"
"$JBR/java" -jar "$TOOLS/usr/lib/android-sdk/build-tools/debian/apksigner.jar" verify \
  --verbose "$BUILD/interval-reminder-debug.apk"
echo "Built $BUILD/interval-reminder-debug.apk"
