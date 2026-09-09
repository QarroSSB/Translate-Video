#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle не найден. Используй GitHub Actions workflow android-debug-apk.yml либо установи Android Studio/Gradle." >&2
  exit 2
fi
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  echo "Android SDK не найден (ANDROID_HOME/ANDROID_SDK_ROOT)." >&2
  exit 2
fi

gradle --no-daemon assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK не найден после сборки" >&2; exit 3; }
echo "$APK"
