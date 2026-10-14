#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d "$ROOT/.local-tools/jdk/Contents/Home" ]]; then
  export JAVA_HOME="$ROOT/.local-tools/jdk/Contents/Home"
fi
if [[ -d "$ROOT/.local-tools/android-sdk" ]]; then
  export ANDROID_HOME="$ROOT/.local-tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.local-tools/gradle}"
exec "$ROOT/android/gradlew" -p "$ROOT/android" "$@"
