#!/usr/bin/env bash
# Mac-side unit tests for the pure-Java parts (AdbLocal, HelperScript, Report). Needs ~/.android/adbkey (made by adb).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  [ -d "$JAVA_HOME" ] || JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')
fi
export JAVA_HOME
rm -rf build/test && mkdir -p build/test
# app sources with the same --release 8 as build.sh, so a Java 9+ API cannot sneak past the tests
"$JAVA_HOME/bin/javac" --release 8 -nowarn -d build/test src/au/jaso/naglab/AdbLocal.java src/au/jaso/naglab/HelperScript.java src/au/jaso/naglab/Report.java
"$JAVA_HOME/bin/javac" -nowarn -cp build/test -d build/test test/au/jaso/naglab/AdbLocalTest.java test/au/jaso/naglab/HelperScriptTest.java test/au/jaso/naglab/ReportTest.java
"$JAVA_HOME/bin/java" -cp build/test au.jaso.naglab.AdbLocalTest
"$JAVA_HOME/bin/java" -cp build/test au.jaso.naglab.HelperScriptTest
"$JAVA_HOME/bin/java" -cp build/test au.jaso.naglab.ReportTest
