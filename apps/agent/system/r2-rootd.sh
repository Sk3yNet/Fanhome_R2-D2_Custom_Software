#!/system/bin/sh
# R2 root helper. Installed as /system/bin/r2-rootd.sh by tools/install-on-robot.ps1 and started as root at
# every boot by /system/etc/install-recovery.sh (Android's flash_recovery service runs that file as root).
#
# Why: R2's /system/bin/su is a Superuser client whose daemon and app aren't on the robot, so it fails for
# everyone and the agent had no way to change system settings (adb over Wi-Fi, the firewall, device nodes).
#
# What it does:
#  1. Early in the boot, closes adb over Wi-Fi (the factory /init.rc opens port 5555 at every boot) unless the
#     agent's "always on" adb setting is stored.
#  2. Runs requests from the agent: shell scripts the agent drops into its own private folder
#     /data/data/dev.r2hack.agent/files/rootq as <id>.req. Only files owned by the agent's user, in that folder
#     owned by the agent's user with mode drwx------, are run, so other apps can't use it. Output goes to
#     <id>.out and the exit code to <id>.rc (see apps/agent/src/dev/r2hack/agent/Root.java).

PKG=dev.r2hack.agent
APP_DIR=/data/data/$PKG
Q=$APP_DIR/files/rootq
PREFS=$APP_DIR/shared_prefs/agent.xml
BB=/sbin/busybox

say() {
    /system/bin/log -t r2-rootd "$*"
}

# Field from "ls -lnd": 1 = mode, 2 = owner uid (toolbox ls).
field() {
    set -- $(ls -lnd "$2" 2> /dev/null)
    eval echo "\${$1}"
}

tcp_port() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$1" -gt 0 ]
}

adb_close() {
    if grep -q 'name="adb-always" value="true"' "$PREFS" 2> /dev/null; then
        say "adb over Wi-Fi left on: the agent's always-on setting is stored"
        return
    fi
    svc=$(getprop service.adb.tcp.port)
    per=$(getprop persist.adb.tcp.port)
    if tcp_port "$svc" || tcp_port "$per"; then
        setprop service.adb.tcp.port -1
        setprop persist.adb.tcp.port -1
        stop adbd
        start adbd
        say "adb over Wi-Fi closed ($1; was service=$svc persist=$per)"
    fi
}

serve() {
    set -- "$Q"/*.req
    [ -e "$1" ] || [ -h "$1" ] || return   # nothing waiting (only shell built-ins so far: cheap when idle)
    owner=$(field 2 "$APP_DIR")
    [ -n "$owner" ] && [ "$owner" != 0 ] || return
    [ "$(field 2 "$Q")" = "$owner" ] || return
    [ "$(field 1 "$Q")" = "drwx------" ] || return
    for req; do
        [ -f "$req" ] || { rm -f "$req"; continue; }
        base=${req%.req}
        if [ "$(field 2 "$req")" != "$owner" ] || [ -h "$req" ]; then
            rm -f "$req"
            continue
        fi
        mv "$req" "$base.run" || continue
        (
            rm -f "$base.tmp" "$base.rc" "$base.out"
            /system/bin/sh "$base.run" > "$base.tmp" 2>&1 < /dev/null
            echo $? > "$base.rc"
            chown "$owner.$owner" "$base.tmp" "$base.rc"
            chmod 600 "$base.tmp" "$base.rc"
            mv "$base.tmp" "$base.out"
            rm -f "$base.run"
        ) &
    done
}

say "started"
# The factory init.rc's "on boot" block sets port 5555; close it once now and again after boot completes in
# case that block runs after us.
sleep 2
adb_close "boot"
rm -f "$Q"/*.run "$Q"/*.tmp 2> /dev/null

booted=0
while true; do
    serve
    if [ $booted = 0 ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
        booted=1
        adb_close "boot completed"
    fi
    $BB usleep 300000 2> /dev/null || sleep 1
done
