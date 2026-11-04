#!/usr/bin/env bash
# Off-robot tests for R2 Main's routine engine (Routine, RoutineRunner, RoutineStore) and places (Places).
# Usage: apps/test.sh
# Needs a JDK and Android's real org.json, taken from Robolectric's android-all jar for Android 4.4
# (the SDK's android.jar only has stubs). The jar is downloaded once to tools/test-libs/, or set
# R2_ANDROID_ALL to a copy you already have.
set -euo pipefail

APPS_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$APPS_DIR")
JAR_NAME=android-all-4.4_r1-robolectric-r2.jar
JAR=${R2_ANDROID_ALL:-$ROOT/tools/test-libs/$JAR_NAME}
if [ ! -f "$JAR" ]; then
    mkdir -p "$(dirname "$JAR")"
    URL_PATH=org/robolectric/android-all/4.4_r1-robolectric-r2/$JAR_NAME
    for repo in https://repo.maven.apache.org/maven2 https://repo1.maven.org/maven2; do
        echo "downloading $repo/$URL_PATH"
        if curl -fsSL --retry 3 -o "$JAR.part" "$repo/$URL_PATH"; then mv "$JAR.part" "$JAR"; break; fi
    done
    [ -f "$JAR" ] || { echo "could not download $JAR_NAME" >&2; exit 1; }
fi

OUT=$APPS_DIR/out/test
rm -rf "$OUT"
mkdir -p "$OUT"
SRC=$APPS_DIR/main/src/dev/r2hack/main
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT" \
    "$SRC/Routine.java" "$SRC/RoutineRunner.java" "$SRC/RoutineStore.java" "$SRC/Places.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/RoutineTest.java" "$APPS_DIR/main/test/dev/r2hack/main/PlacesTest.java"
java -cp "$OUT:$JAR" dev.r2hack.main.RoutineTest "$ROOT/docs/routines"
java -cp "$OUT:$JAR" dev.r2hack.main.PlacesTest
