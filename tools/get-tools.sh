#!/usr/bin/env bash
# Fetch the two Android SDK pieces build.sh needs, straight from Google (no Android Studio,
# no sdkmanager): build-tools 36 (aapt2, d8, zipalign, apksigner) and the API 30 android.jar.
# Puts them in tools/build-tools/ and tools/platforms/android-30.jar (both gitignored).
set -euo pipefail
cd "$(dirname "$0")"

case "$(uname -s)" in
  Darwin) BT_ZIP=build-tools_r36_macosx.zip ;;
  Linux)  BT_ZIP=build-tools_r36_linux.zip ;;
  *) echo "unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
BASE=https://dl.google.com/android/repository

if [ ! -x build-tools/aapt2 ]; then
  echo "downloading $BT_ZIP (~75 MB)"
  curl -fL -o "$BT_ZIP" "$BASE/$BT_ZIP"
  rm -rf build-tools bt.tmp && mkdir bt.tmp
  unzip -q "$BT_ZIP" -d bt.tmp
  mv bt.tmp/* build-tools        # zip contains one dir (android-16 or similar)
  rmdir bt.tmp
  rm -f "$BT_ZIP"
fi

if [ ! -f platforms/android-30.jar ]; then
  echo "downloading platform-30_r03.zip (~50 MB)"
  curl -fL -o platform-30.zip "$BASE/platform-30_r03.zip"
  mkdir -p platforms
  unzip -q -j -o platform-30.zip '*/android.jar' -d platforms
  mv platforms/android.jar platforms/android-30.jar
  rm -f platform-30.zip
fi

echo "ok: $(./build-tools/aapt2 version 2>&1 | head -1), $(wc -c < platforms/android-30.jar | tr -d ' ') bytes android-30.jar"
