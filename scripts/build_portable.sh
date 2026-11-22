#!/usr/bin/env bash
set -euo pipefail

# Portable low-memory build & JVM test runner for interval-reminder-android.
# Discovers JDK 17+ and Android SDK from environment or /home/box/android-toolchain.
# Never builds or signs a release APK, never accesses API keys, and keeps memory bounded.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_ROOT="${ANDROID_TOOLCHAIN_ROOT:-$HOME/android-toolchain}"

find_java() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi
  for candidate in "$TOOLCHAIN_ROOT"/jdk* /usr/lib/jvm/java-17-* /usr/lib/jvm/default-java; do
    if [[ -d "$candidate" && -x "$candidate/bin/javac" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if command -v javac >/dev/null 2>&1; then
    dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
    return 0
  fi
  return 1
}

find_sdk() {
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$TOOLCHAIN_ROOT/android-sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

JAVA_HOME="$(find_java)" || {
  echo "ERROR: Compatible JDK (17+) not found. Set JAVA_HOME or install under $TOOLCHAIN_ROOT/jdk-17." >&2
  exit 1
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "Using JAVA_HOME=$JAVA_HOME"
java -version 2>&1 | head -n 3

run_jvm_tests() {
  local json_jar="$TOOLCHAIN_ROOT/lib/json.jar"
  if [[ ! -f "$json_jar" ]]; then
    echo "ERROR: $json_jar not found for JVM tests." >&2
    exit 1
  fi

  local out="$ROOT/build/jvm-tests"
  rm -rf "$out"
  mkdir -p "$out"

  echo "Compiling pure-JVM unit tests (ScheduleTest, MimoTtsTest, CardStyleTest, AppThemeTest) ..."
  javac --release 8 -cp "$json_jar" -d "$out" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/AppTheme.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/Base64Compat.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/CardStyle.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/MimoTtsProtocol.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/Schedule.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/VoiceConsent.java" \
    "$ROOT/app/src/main/java/com/example/reviewreminder/WavAudio.java" \
    "$ROOT/tests/ScheduleTest.java" \
    "$ROOT/tests/MimoTtsTest.java" \
    "$ROOT/tests/CardStyleTest.java" \
    "$ROOT/tests/AppThemeTest.java"

  (
    cd "$ROOT"
    java -Xmx256m -cp "$out:$json_jar" ScheduleTest
    java -Xmx256m -cp "$out:$json_jar" MimoTtsTest
    java -Xmx256m -cp "$out:$json_jar" CardStyleTest
    java -Xmx256m -cp "$out:$json_jar" AppThemeTest
  )

  local sdk=""
  sdk="$(find_sdk 2>/dev/null || true)"
  local android_jar=""
  if [[ -n "$sdk" ]]; then
    android_jar="$(find "$sdk/platforms" -maxdepth 2 -name android.jar 2>/dev/null | sort -V | tail -n 1 || true)"
  fi
  if [[ -n "$android_jar" ]]; then
    local core_out="$ROOT/build/jvm-core-tests"
    rm -rf "$core_out"
    mkdir -p "$core_out"
    echo "Compiling CoreTest against $json_jar:$android_jar ..."
    javac --release 8 -cp "$json_jar:$android_jar" -d "$core_out" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/Base64Compat.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/IntervalPlan.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/LegacyMigration.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/LocalStore.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/PlanIdentity.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/ReviewSession.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/Schedule.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/SpellingPlan.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/SpellingSession.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/Store.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/TaskIndex.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/TaskTitle.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/WordGraph.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/WordParser.java" \
      "$ROOT/app/src/main/java/com/example/reviewreminder/Words.java" \
      "$ROOT/tests/CoreTest.java"
    (
      cd "$ROOT"
      java -Xmx256m -cp "$core_out:$json_jar:$android_jar" CoreTest
    )
  else
    echo "NOTE: Skipping CoreTest (requires android.jar from Android SDK for Words.java compile)."
  fi
}

build_debug_apk() {
  local sdk
  sdk="$(find_sdk)" || {
    echo "ERROR: Android SDK (platforms/android-35 + build-tools) not found under $TOOLCHAIN_ROOT/android-sdk." >&2
    exit 1
  }
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLCHAIN_ROOT/gradle-home}"
  export GRADLE_OPTS="${GRADLE_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"
  mkdir -p "$GRADLE_USER_HOME"

  echo "Using ANDROID_HOME=$ANDROID_HOME"
  (
    cd "$ROOT"
    ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=256m" assembleDebug
  )
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ -f "$apk" ]]; then
    ls -lh "$apk"
    local apksigner
    apksigner="$(find "$sdk/build-tools" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
    if [[ -n "$apksigner" ]]; then
      "$apksigner" verify --print-certs "$apk"
    fi
  fi
}

MODE="${1:-all}"
case "$MODE" in
  test) run_jvm_tests ;;
  apk) build_debug_apk ;;
  all)
    run_jvm_tests
    build_debug_apk
    ;;
  *)
    echo "Usage: $0 [test|apk|all]" >&2
    exit 2
    ;;
esac
