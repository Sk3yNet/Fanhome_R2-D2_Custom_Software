package dev.r2hack.agent;

import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * adb over Wi-Fi is a root shell with no authentication on this build, so it is off unless asked for.
 * POST /adb?on=1 switches it on for a limited time (default 30 minutes), then it goes off by itself.
 * While it is off, adbd is back on USB as usual. Each call to /adb?on=1 restarts the timer.
 *
 * "always" keeps the old behaviour (on across reboots) for development on a trusted network; it is
 * a stored setting and shows up in /status. The serial console works regardless.
 */
final class AdbControl {
    static final int PORT = 5555;
    private static final String PREF_ALWAYS = "adb-always";

    private final SharedPreferences prefs;
    private volatile long offAt;      // elapsedRealtime when the timed window ends, 0 = no window
    private volatile boolean on;

    AdbControl(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** At agent start: apply the stored setting and start the timer that switches adb off again. */
    void start() {
        if (always()) {
            apply(true, "always on (setting)");
        } else {
            apply(false, "off by default at agent start");
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int tick = 0;
                while (true) {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                    }
                    long end = offAt;
                    if (end != 0 && SystemClock.elapsedRealtime() >= end && !always()) {
                        offAt = 0;
                        apply(false, "timer ran out");
                    } else if (++tick % 30 == 0 && !on && !always() && isOn()) {
                        // Every 5 minutes: something (a failed switch, the factory boot script) left it open.
                        synchronized (AdbControl.this) {
                            if (!on && offAt == 0) apply(false, "found open while it should be off");
                        }
                    }
                }
            }
        }, "adb-timer");
        t.setDaemon(true);
        t.start();
    }

    /** Switches Wi-Fi adb on for this many minutes (1 to Config.ADB_MAX_MINUTES). */
    synchronized String enable(int minutes) {
        offAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
        if (!on) apply(true, "on for " + minutes + " min (API)");
        else EventLog.i("adb over Wi-Fi: timer reset to " + minutes + " min");
        return "adb over Wi-Fi on port " + PORT + " for " + minutes + " minutes";
    }

    synchronized String disable() {
        prefs.edit().putBoolean(PREF_ALWAYS, false).commit();
        offAt = 0;
        apply(false, "off (API)");
        return "adb over Wi-Fi off (adb is back on USB)";
    }

    synchronized String setAlways() {
        prefs.edit().putBoolean(PREF_ALWAYS, true).commit();
        offAt = 0;
        apply(true, "always on (API)");
        return "adb over Wi-Fi on, also after reboots. Anyone on this network gets a root shell; "
                + "POST /adb?on=0 to undo.";
    }

    boolean always() {
        return prefs.getBoolean(PREF_ALWAYS, false);
    }

    /** Whether adbd really listens on Wi-Fi, from the properties (not just what was asked for). */
    boolean isOn() {
        return listening(Supervisor.run("getprop", "service.adb.tcp.port"), Supervisor.run("getprop", "persist.adb.tcp.port"));
    }

    /** What was last asked for (on or off); differs from isOn() when switching failed. */
    boolean wanted() {
        return on;
    }

    /** Seconds until the timed window closes, 0 if none (or always on). */
    long secondsLeft() {
        long end = offAt;
        return end == 0 ? 0 : Math.max(0, (end - SystemClock.elapsedRealtime()) / 1000);
    }

    /**
     * KitKat's adbd listens on TCP when service.adb.tcp.port (or, if that's unset, persist.adb.tcp.port)
     * is a port number, and on USB otherwise. Both are set, so an old persist value from the install
     * script can't turn it back on at the next boot, and adbd is restarted to pick it up.
     * The factory /init.rc sets service.adb.tcp.port to 5555 at every boot; the root helper (r2-rootd.sh)
     * closes it again early in the boot, and this does it again when the agent starts.
     * Setting the properties needs root (see Root); the result is read back, so a failure shows in the log and
     * in /status (adb_wifi follows the real properties) instead of being reported as done.
     */
    private void apply(boolean wantOn, String why) {
        String service = wantOn ? String.valueOf(PORT) : "-1";
        String persist = wantOn && always() ? String.valueOf(PORT) : "-1";
        on = wantOn;
        boolean persistOk = persist.equals(Supervisor.run("getprop", "persist.adb.tcp.port"));
        if (persistOk && tcpPort(Supervisor.run("getprop", "service.adb.tcp.port")) == wantOn) {
            EventLog.i("adb over Wi-Fi " + (wantOn ? "on" : "off") + ": " + why + " (already)");
            return; // no adbd restart, so an open USB session survives an agent restart
        }
        String out = Root.run("setprop service.adb.tcp.port " + service
                + "; setprop persist.adb.tcp.port " + persist + "; stop adbd; start adbd");
        boolean nowOn = isOn();
        if (nowOn != wantOn) {
            EventLog.i("adb over Wi-Fi: FAILED to switch " + (wantOn ? "on" : "off") + " (" + why + "), it is still "
                    + (nowOn ? "on" : "off") + (out.isEmpty() ? "" : ": " + out));
            return;
        }
        EventLog.i("adb over Wi-Fi " + (wantOn ? "on" : "off") + ": " + why + (out.isEmpty() ? "" : " (" + out + ")"));
    }

    /** Whether adbd listens on TCP with these property values: the service one wins when it is set. */
    static boolean listening(String service, String persist) {
        String s = service == null ? "" : service.trim();
        return s.isEmpty() ? tcpPort(persist == null ? "" : persist) : tcpPort(s);
    }

    /** Whether a property value makes adbd listen on TCP (a port number above 0). */
    static boolean tcpPort(String value) {
        try {
            return Integer.parseInt(value.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
