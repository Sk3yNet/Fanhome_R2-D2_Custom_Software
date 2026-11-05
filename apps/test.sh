#!/usr/bin/env bash
# Off-robot tests: R2 Main's routine engine (Routine, RoutineRunner, RoutineStore, Triggers), places (Places),
# watch-with-me (CueFile, CueStore, WatchWithMe), idle personality (IdlePersonality), sound reactions
# (SoundReactions), "follow me"
# (FollowTracker, FollowControl, Follower), Home Assistant link
# (HomeAssistant, without a broker), the chirp generator (Chirp), Home Assistant voice replies (Assist, Beeps),
# the plugin interface (PluginManager,
# PluginContext, the built-in plugins), the agent's hotspot password helpers (Hotspot) and its boot
# hardening rules (HardeningRules; the firewall scripts also run against a real iptables in a throwaway
# network namespace when this machine allows one) and security (signed requests, clients and permissions,
# the APK signature check, and the web page's signing code). Example chirps are written to
# apps/out/chirps/*.wav for listening.
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
    "$SRC/Routine.java" "$SRC/RoutineRunner.java" "$SRC/RoutineStore.java" "$SRC/Triggers.java" "$SRC/Places.java" "$SRC/Chirp.java" \
    "$SRC/CueFile.java" "$SRC/CueStore.java" "$SRC/WatchWithMe.java" "$SRC/IdlePersonality.java" \
    "$SRC/SoundReactions.java" "$SRC/HomeAssistant.java" "$SRC/MqttClient.java" "$SRC/LocalNet.java" \
    "$SRC/FollowTracker.java" "$SRC/FollowControl.java" "$SRC/Follower.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/RoutineTest.java" "$APPS_DIR/main/test/dev/r2hack/main/PlacesTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/WatchTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/IdleTest.java" "$APPS_DIR/main/test/dev/r2hack/main/HomeAssistantTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/ChirpTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/SoundReactionsTest.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/FollowTest.java"
java -cp "$OUT:$JAR" dev.r2hack.main.RoutineTest "$ROOT/docs/routines"
java -cp "$OUT:$JAR" dev.r2hack.main.PlacesTest
java -cp "$OUT:$JAR" dev.r2hack.main.WatchTest "$ROOT/docs/watch-with-me"
java -cp "$OUT:$JAR" dev.r2hack.main.IdleTest
java -cp "$OUT:$JAR" dev.r2hack.main.HomeAssistantTest
java -cp "$OUT:$JAR" dev.r2hack.main.SoundReactionsTest
java -cp "$OUT:$JAR" dev.r2hack.main.ChirpTest "$APPS_DIR/out/chirps"
java -cp "$OUT:$JAR" dev.r2hack.main.FollowTest

AGENT_SRC=$APPS_DIR/agent/src/dev/r2hack/agent
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT" \
    "$AGENT_SRC/Hotspot.java" "$AGENT_SRC/EventLog.java" \
    "$APPS_DIR/agent/test/dev/r2hack/agent/HotspotTest.java"
java -cp "$OUT:$JAR" dev.r2hack.agent.HotspotTest

javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR:$OUT" -d "$OUT" \
    "$SRC/Plugin.java" "$SRC/PluginPermissions.java" "$SRC/PluginContext.java" "$SRC/PluginManager.java" \
    "$SRC/BatteryAlertPlugin.java" "$SRC/HomeAssistantPlugin.java" "$SRC/HomeAssistant.java" \
    "$SRC/MqttClient.java" "$SRC/LocalNet.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/PluginTest.java"
java -cp "$OUT:$JAR" dev.r2hack.main.PluginTest

# Boot hardening (agent): rules and parsers, then the generated firewall scripts on a real iptables.
AGENT=$APPS_DIR/agent
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT" \
    "$AGENT/src/dev/r2hack/agent/HardeningRules.java" "$AGENT/test/dev/r2hack/agent/HardeningTest.java"
SCRIPTS=$OUT/hardening-scripts
mkdir -p "$SCRIPTS"
java -cp "$OUT:$JAR" dev.r2hack.agent.HardeningTest "$SCRIPTS"

NETNS_CHECK='
set -e
cd "$1"
sh fw4.sh | tail -1 | grep -qx OK
iptables -S INPUT | grep -qx -- "-A INPUT -j r2_in"
sh fw4.sh > /dev/null                                    # again: still exactly one jump
[ "$(iptables -S INPUT | grep -c r2_in)" = 1 ]
iptables -S r2_in | tail -1 | grep -qx -- "-A r2_in -j DROP"
sh fw4-conntrack.sh | tail -1 | grep -qx OK
sh fw6.sh | tail -1 | grep -qx OK
ip6tables -S INPUT | grep -qx -- "-A INPUT -j r2_in6"
sh fw4-off.sh; sh fw6-off.sh; sh fw4-off.sh
! iptables -S | grep -q r2_in
! ip6tables -S | grep -q r2_in6
iptables -S INPUT | grep -qx -- "-P INPUT ACCEPT"
'
if command -v iptables > /dev/null && command -v ip6tables > /dev/null; then
    if unshare -rn true 2> /dev/null; then RUN="unshare -rn"
    elif sudo -n unshare -n true 2> /dev/null; then RUN="sudo -n unshare -n"
    else RUN=""; fi
    if [ -n "$RUN" ]; then
        $RUN sh -c "$NETNS_CHECK" sh "$SCRIPTS"
        echo "firewall scripts work on a real iptables"
    else
        echo "skipped: no network namespace available to try the firewall scripts in"
    fi
else
    echo "skipped: no iptables here to try the firewall scripts on"
fi

# Home Assistant voice replies (Assist, Beeps). Android's Log is native, so a stand-in is compiled first
# on the class path.
ASSIST_OUT=$APPS_DIR/out/test-assist
rm -rf "$ASSIST_OUT"
mkdir -p "$ASSIST_OUT"
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$ASSIST_OUT" \
    -sourcepath "$APPS_DIR/main/src" "$APPS_DIR/main/test/android/util/Log.java" "$SRC/Assist.java" "$SRC/Beeps.java" \
    "$APPS_DIR/main/test/dev/r2hack/main/AssistTest.java"
java -cp "$ASSIST_OUT:$JAR" dev.r2hack.main.AssistTest

# Agent security. Test "APKs" are JARs signed with a throwaway key: the check reads the same JAR signature.
SEC_SRC=$APPS_DIR/agent/src/dev/r2hack/agent
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT" \
    "$SEC_SRC/RequestAuth.java" "$SEC_SRC/Clients.java" "$SEC_SRC/Permissions.java" "$SEC_SRC/ApkTrust.java" \
    "$APPS_DIR/agent/test/dev/r2hack/agent/SecurityTest.java"
APK=$OUT/apk
mkdir -p "$APK/files"
echo "classes" > "$APK/files/classes.dex"
echo "manifest" > "$APK/files/AndroidManifest.xml"
(cd "$APK/files" && jar cf "$APK/unsigned.apk" classes.dex AndroidManifest.xml)
keytool -genkeypair -keystore "$APK/test.keystore" -storepass test-pass -keypass test-pass -alias t \
    -keyalg RSA -keysize 2048 -validity 1 -dname CN=r2-test > /dev/null 2>&1
cp "$APK/unsigned.apk" "$APK/signed.apk"
jarsigner -keystore "$APK/test.keystore" -storepass test-pass "$APK/signed.apk" t > /dev/null
CERT_SHA=$(keytool -exportcert -keystore "$APK/test.keystore" -storepass test-pass -alias t 2>/dev/null \
    | sha256sum | cut -d' ' -f1)
cp "$APK/signed.apk" "$APK/tampered.apk"
echo "changed after signing" > "$APK/files/classes.dex"
(cd "$APK/files" && jar uf "$APK/tampered.apk" classes.dex)
java -cp "$OUT:$JAR" dev.r2hack.agent.SecurityTest "$APK/signed.apk" "$APK/tampered.apk" "$APK/unsigned.apk" "$CERT_SHA" \
    | tee "$OUT/security.txt"

# The web page signs requests in JavaScript; check it against the same vector.
if command -v node > /dev/null; then
    WANT=$(sed -n 's/^vector: key=test-key sig=//p' "$OUT/security.txt")
    node "$APPS_DIR/agent/test/sign-test.js" "$APPS_DIR/agent/web/index.html" "$WANT"
else
    echo "(skipping the web page signing test: node not found)"
fi
