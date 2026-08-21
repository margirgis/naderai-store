#!/bin/bash
# build_release.sh — بناء APK release على جهاز Android developer
# الاستخدام: ./build_release.sh /path/to/android-sdk
# مثال:     ./build_release.sh $ANDROID_HOME

set -e

SDK_DIR="${1:-$ANDROID_HOME}"
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then
  echo "❌ ANDROID_SDK not found. Pass path as first arg or set ANDROID_HOME"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# كتابة local.properties
echo "sdk.dir=$SDK_DIR" > local.properties

echo "🔨 Building v1.1.58 release APK..."
./gradlew assembleRelease --no-daemon

APK_SRC="app/build/outputs/apk/release/app-release.apk"
APK_DST="../../releases/naderai-sms-reader-v1.1.58.apk"

mkdir -p "$(dirname "$APK_DST")"
cp "$APK_SRC" "$APK_DST"

echo "✅ APK ready: $APK_DST"
echo ""
echo "Git commit & tag:"
echo "  git -C ../.. add tasks/releases/naderai-sms-reader-v1.1.58.apk"
echo "  git -C ../.. commit --amend --no-edit"
echo "  git -C ../.. tag v1.1.58"
echo "  git -C ../.. push origin main --tags"
