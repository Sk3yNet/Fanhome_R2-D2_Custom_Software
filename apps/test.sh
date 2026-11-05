#!/usr/bin/env bash
# Off-robot tests: R2 Main's routine engine (Routine, RoutineRunner, RoutineStore), places (Places),
# watch-with-me (CueFile, CueStore, WatchWithMe), idle personality (IdlePersonality), Home Assistant link
# (HomeAssistant, without a broker), the chirp generator (Chirp) and the agent's hotspot password helpers
# (Hotspot). Example chirps are written to apps/out/chirps/*.wav for listening.
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
    "$SRC/Routine.java" "$SRC/RoutineRunner.java" "$SRC/RoutineStore.java" "$SRC/Places.java" "$SRC/Chirp.java" \
    "$SRC/CueFile.java" "$SRC/CueStore.java" "$SRC/WatchWithMe.java" "$SRC/IdlePersonality.java" \
    "$SRC/HomeAssistant.java" "$SRC/MqttClient.java" "$SRC/LocalNet.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/RoutineTest.java" "$APPS_DIR/main/test/dev/r2hack/main/PlacesTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/WatchTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/IdleTest.java" "$APPS_DIR/main/test/dev/r2hack/main/HomeAssistantTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/ChirpTest.java"
java -cp "$OUT:$JAR" dev.r2hack.main.RoutineTest "$ROOT/docs/routines"
java -cp "$OUT:$JAR" dev.r2hack.main.PlacesTest
java -cp "$OUT:$JAR" dev.r2hack.main.WatchTest "$ROOT/docs/watch-with-me"
java -cp "$OUT:$JAR" dev.r2hack.main.IdleTest
java -cp "$OUT:$JAR" dev.r2hack.main.HomeAssistantTest
java -cp "$OUT:$JAR" dev.r2hack.main.ChirpTest "$APPS_DIR/out/chirps"

AGENT_SRC=$APPS_DIR/agent/src/dev/r2hack/agent
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT" \
    "$AGENT_SRC/Hotspot.java" "$AGENT_SRC/EventLog.java" \
    "$APPS_DIR/agent/test/dev/r2hack/agent/HotspotTest.java"
java -cp "$OUT:$JAR" dev.r2hack.agent.HotspotTest
