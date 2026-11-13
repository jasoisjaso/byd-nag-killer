#!/usr/bin/env bash
# Fetches what app/test/screens.sh needs to draw the app's screens on a computer: Robolectric
# and the Android 11 it runs (about 330 MB) into tools/screens/. None of it goes into the app.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p screens

while read -r url; do
  [ -n "$url" ] || continue
  name="${url##*/}"
  case "$name" in
    *.aar) out="screens/${name%.aar}.jar" ;;   # an .aar is a zip with the classes inside
    *) out="screens/$name" ;;
  esac
  [ -s "$out" ] && continue
  echo "fetching $name"
  curl -fsSL -o "screens/$name.part" "$url"
  case "$name" in
    *.aar)
      unzip -p "screens/$name.part" classes.jar > "$out"
      rm "screens/$name.part"
      ;;
    *) mv "screens/$name.part" "$out" ;;
  esac
done <<'EOF'
https://repo1.maven.org/maven2/org/robolectric/robolectric/4.16.1/robolectric-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/annotations/4.16.1/annotations-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/junit/4.16.1/junit-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/nativeruntime/4.16.1/nativeruntime-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/nativeruntime-dist-compat/1.0.18/nativeruntime-dist-compat-1.0.18.jar
https://repo1.maven.org/maven2/org/robolectric/pluginapi/4.16.1/pluginapi-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/plugins-maven-dependency-resolver/4.16.1/plugins-maven-dependency-resolver-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/resources/4.16.1/resources-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/sandbox/4.16.1/sandbox-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/shadowapi/4.16.1/shadowapi-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/shadows-framework/4.16.1/shadows-framework-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/utils/4.16.1/utils-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/utils-reflector/4.16.1/utils-reflector-4.16.1.jar
https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/11-robolectric-6757853-i7/android-all-instrumented-11-robolectric-6757853-i7.jar
https://maven.google.com/androidx/test/monitor/1.8.0/monitor-1.8.0.aar
https://maven.google.com/androidx/test/espresso/espresso-idling-resource/3.7.0/espresso-idling-resource-3.7.0.aar
https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
https://repo1.maven.org/maven2/com/almworks/sqlite4java/sqlite4java/1.0.392/sqlite4java-1.0.392.jar
https://repo1.maven.org/maven2/com/google/auto/value/auto-value-annotations/1.11.0/auto-value-annotations-1.11.0.jar
https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotation/2.41.0/error_prone_annotation-2.41.0.jar
https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.36.0/error_prone_annotations-2.36.0.jar
https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar
https://repo1.maven.org/maven2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar
https://repo1.maven.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar
https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0.jar
https://repo1.maven.org/maven2/com/google/testparameterinjector/test-parameter-injector/1.18/test-parameter-injector-1.18.jar
https://repo1.maven.org/maven2/com/ibm/icu/icu4j/77.1/icu4j-77.1.jar
https://repo1.maven.org/maven2/javax/annotation/javax.annotation-api/1.3.2/javax.annotation-api-1.3.2.jar
https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar
https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.81/bcprov-jdk18on-1.81.jar
https://repo1.maven.org/maven2/org/conscrypt/conscrypt-openjdk-uber/2.5.2/conscrypt-openjdk-uber-2.5.2.jar
https://repo1.maven.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar
https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar
https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar
https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar
https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar
EOF
echo "screen tools ready in $(pwd)/screens"
