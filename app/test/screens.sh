#!/usr/bin/env bash
# Draws the app's real screens on this computer (no car needed) and checks the layout: no text
# cut off, no big figures running into each other. Pictures land in build/screens/.
# Run tools/get-screen-tools.sh once first. Needs a JDK 17 to 21 to run it (SCREENS_JAVA_HOME).
set -euo pipefail
cd "$(dirname "$0")/.."

BT="${BT:-../tools/build-tools}"
ANDROID_JAR="${ANDROID_JAR:-../tools/platforms/android-30.jar}"
LIB="${SCREEN_TOOLS:-../tools/screens}"
if [ -z "${SCREENS_JAVA_HOME:-}" ]; then
  SCREENS_JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  [ -d "$SCREENS_JAVA_HOME" ] || SCREENS_JAVA_HOME="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
fi
JAVAC="$SCREENS_JAVA_HOME/bin/javac"
JAVA="$SCREENS_JAVA_HOME/bin/java"
[ -x "$JAVAC" ] || { echo "no javac at '$SCREENS_JAVA_HOME' (set SCREENS_JAVA_HOME to a JDK 17 to 21)" >&2; exit 1; }
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$BT/aapt2" ]; then
  echo "build tools missing: run tools/get-tools.sh first" >&2
  exit 1
fi
if ! ls "$LIB"/robolectric-*.jar >/dev/null 2>&1; then
  echo "screen tools missing: run tools/get-screen-tools.sh first" >&2
  exit 1
fi

OUT=build/screens
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/assets" "$OUT/cfg/com/android/tools"
cp watchdog.sh "$OUT/assets/naglab_watchdog.sh"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/res.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  -A "$OUT/assets" --java "$OUT/gen" "$OUT/res.zip"

CP=$(printf '%s:' "$LIB"/*.jar)
# shellcheck disable=SC2046  # source paths contain no whitespace
"$JAVAC" --release 8 -XDstringConcat=inline -nowarn -Xlint:-options -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$JAVAC" -nowarn -classpath "$OUT/classes:$ANDROID_JAR:$CP" -d "$OUT/classes" \
  test/au/jaso/naglab/ScreensTest.java

# where Robolectric finds the app's manifest and pictures
cat > "$OUT/cfg/com/android/tools/test_config.properties" <<EOF
android_merged_manifest=$PWD/AndroidManifest.xml
android_resource_apk=$PWD/$OUT/res.apk
android_merged_assets=$PWD/$OUT/assets
android_custom_package=au.jaso.naglab
EOF

"$JAVA" -Duser.timezone="${SCREENS_TZ:-Australia/Brisbane}" -Drobolectric.dependency.dir="$LIB" \
  -Dscreens.out="$PWD/$OUT" -cp "$OUT/classes:$OUT/cfg:$CP$ANDROID_JAR" \
  org.junit.runner.JUnitCore au.jaso.naglab.ScreensTest
echo "pictures: $PWD/$OUT/*.png"
