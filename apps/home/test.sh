#!/usr/bin/env bash
# Runs the plain-JVM model tests. Needs JAVA_HOME and ANDROID_SDK_ROOT like build.sh (android.jar only).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../env.sh"
need_jdk
need_android_jar
OUT="$HERE/build/test"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT" \
  "$HERE/src/tv/openshelf/home/Entry.java" "$HERE/src/tv/openshelf/home/HomeModel.java" \
  "$HERE/src/tv/openshelf/home/Prefs.java" "$HERE/test/tv/openshelf/home/HomeModelTest.java"
java -cp "$OUT:$ANDROID_JAR" tv.openshelf.home.HomeModelTest
