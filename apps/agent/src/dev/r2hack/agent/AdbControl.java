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
                while (true) {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                    }
                    long end = offAt;
                    if (end != 0 && SystemClock.elapsedRealtime() >= end && !always()) {
                        offAt = 0;
                        apply(false, "timer ran out");
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

    boolean isOn() {
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
        String out = Supervisor.run("su", "-c", "setprop service.adb.tcp.port " + service
                + "; setprop persist.adb.tcp.port " + persist + "; stop adbd; start adbd");
        EventLog.i("adb over Wi-Fi " + (wantOn ? "on" : "off") + ": " + why + (out.isEmpty() ? "" : " (" + out + ")"));
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
