#!/usr/bin/env bash
# Build the Nag Killer APK with bare build-tools (no Gradle).
# Needs: JDK (javac), android-30 android.jar, build-tools (aapt2, d8, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"

BT="${BT:-../tools/build-tools}"                        # build-tools 36; r34 d8 crashes on JDK 27
ANDROID_JAR="${ANDROID_JAR:-../tools/platforms/android-30.jar}"
if [ -z "${JAVA_HOME:-}" ]; then   # Homebrew JDK on a Mac, else whatever java is on PATH
  JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  [ -d "$JAVA_HOME" ] || JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')
fi
export JAVA_HOME
[ -x "$JAVA_HOME/bin/javac" ] || { echo "no javac at '$JAVA_HOME' (install a JDK 17+ or set JAVA_HOME)" >&2; exit 1; }
JAVAC="$JAVA_HOME/bin/javac"
KEYSTORE="keys/naglab.keystore"   # created on first build; KEEP IT: a new key cannot update the installed app

if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$BT/aapt2" ]; then
  echo "build tools missing: run tools/get-tools.sh first" >&2
  exit 1
fi

rm -rf build && mkdir -p build/classes build/dex build/assets build/gen
cp watchdog.sh build/assets/naglab_watchdog.sh   # the app uploads it to /data/local/tmp itself

# Resources (launcher icon + drawables). Link first: it generates R.java for javac.
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  -A build/assets --java build/gen build/res.zip

# shellcheck disable=SC2046  # source paths contain no whitespace
"$JAVAC" --release 8 -XDstringConcat=inline -nowarn -classpath "$ANDROID_JAR" -d build/classes \
  $(find src build/gen -name '*.java')
# shellcheck disable=SC2046
"$BT/d8" --min-api 29 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass naglab -keypass naglab \
    -alias naglab -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NagLab" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:naglab --key-pass pass:naglab \
  --out build/naglab.apk build/aligned.apk
"$BT/apksigner" verify build/naglab.apk
echo "built $(pwd)/build/naglab.apk"
