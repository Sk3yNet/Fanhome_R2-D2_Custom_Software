package dev.r2hack.agent;

import org.json.JSONObject;

/**
 * Safety layer on every command headed for the controller, whoever sent it:
 *  - motion watchdog: a drive command only lasts a few seconds unless it is repeated, then the
 *    agent sends stop itself, so a hung or crashed app (or a lost Wi-Fi client) can't leave R2 driving;
 *  - head watchdog: a continuous head turn (head-dir) stops unless it is repeated;
 *  - low battery: below Config.LOW_BATTERY_PCT, new drive commands are refused and driving is stopped;
 *  - drive lock (DriveLock): when it is on, drive commands are refused until driving is enabled, and
 *    driving stops when the enable runs out or is taken back.
 * Stop commands always go through. Lives in the agent so it keeps working when the main app is hung.
 * No Android classes, so it can be tested off the robot (apps/test.sh).
 */
final class MotionGuard {
    interface Output {
        boolean write(String line);

        void log(String msg);

        /** The drive lock's state changed (locked, enabled, lock on or off). */
        void onDriveChanged();

        /** A drive command was refused because driving is locked (at most every REFUSED_NOTICE_MS). */
        void onDriveRefused();
    }

    static final long REFUSED_NOTICE_MS = 2_000;

    static final String STOP_MOVE = "{\"cmd\":\"move\",\"power\":0,\"angle\":0}";
    static final String STOP_MODE = "{\"cmd\":\"mode\",\"mode\":0}";
    static final String STOP_HEAD = "{\"cmd\":\"head-dir\",\"dir\":0}";

    private final Output out;
    private volatile long motionUntil;   // 0 = not moving
    private volatile long headUntil;     // 0 = head not turning (head-dir)
    private volatile int battery = -1;   // from gin, -1 until the first report
    private volatile boolean charging;
    private final DriveLock lock;
    private volatile long lastRefusedNotice;

    MotionGuard(Output out, DriveLock lock) {
        this.out = out;
        this.lock = lock;
    }

    DriveLock driveLock() {
        return lock;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    tick();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "motion-guard");
        t.setDaemon(true);
        t.start();
    }

    /** One pass of the watchdogs (every 200 ms). */
    void tick() {
        long now = System.currentTimeMillis();
        long until = motionUntil;
        if (until != 0 && now > until) stop("motion watchdog: no new drive command");
        long head = headUntil;
        if (head != 0 && now > head) {
            headUntil = 0;
            out.write(STOP_HEAD);
        }
        if (motionUntil != 0) lock.touch(); // still driving (a long patrol, say): not idle
        if (lock.expire()) {
            out.log("drive lock: driving was idle for " + lock.idleMinutes() + " min, locked again");
            out.onDriveChanged();
        }
    }

    /** Enables driving for this many idle minutes (lock on), see DriveLock. */
    void enableDriving(int minutes) {
        lock.enable(minutes);
        out.log("drive lock: driving enabled until idle for " + minutes + " min");
        out.onDriveChanged();
    }

    /** Takes driving back (lock on); stops R2 if it is moving. */
    void disableDriving(String why) {
        boolean was = lock.enabled();
        lock.disable();
        if (lock.required() && motionUntil != 0) stop("driving locked: " + why);
        if (was) {
            out.log("drive lock: driving locked (" + why + ")");
            out.onDriveChanged();
        }
    }

    /** Switches the lock itself on or off. Switching it on locks driving at once and stops R2. */
    void setLockRequired(boolean on) {
        lock.setRequired(on);
        if (on && motionUntil != 0) stop("drive lock switched on");
        out.log("drive lock " + (on ? "on: driving needs enabling" : "off: driving always allowed"));
        out.onDriveChanged();
    }

    int battery() {
        return battery;
    }

    boolean lowBattery() {
        return battery >= 0 && battery < Config.LOW_BATTERY_PCT && !charging;
    }

    boolean moving() {
        return motionUntil != 0 || headUntil != 0;
    }

    /**
     * Where the head was last sent (degrees, 0 = forward), so relative head-shift steps can be capped too
     * and the head never runs into its end stops.
     */
    private int headPos;
    private boolean headKnown = true;   // false after a continuous turn (head-dir): position unknown

    private static int clampHead(int v) {
        return Math.max(-Config.HEAD_LIMIT, Math.min(Config.HEAD_LIMIT, v));
    }

    /** Whether a command makes R2's wheels move (move with power, or a driving controller mode). */
    static boolean drives(String line) {
        return holdFor(line) > 0;
    }

    /** How long a drive command lasts without a repeat; 0 if it doesn't drive. */
    private static long holdFor(String line) {
        try {
            JSONObject c = new JSONObject(line);
            String cmd = c.optString("cmd");
            if ("move".equals(cmd)) return c.optInt("power") == 0 ? 0 : Config.MOVE_HOLD_MS;
            if ("mode".equals(cmd)) {
                int m = c.optInt("mode", -1);
                if (m == 9) return Config.PATROL_HOLD_MS;
                if (m == 2 || m == 3 || m == 4 || m == 5 || m == 12) return Config.MODE_HOLD_MS;
            }
        } catch (Exception notJson) {
            // passed through unchanged, like the stock app's raw lines
        }
        return 0;
    }

    /** Why this command would be refused now, or null if it may go out. */
    String refusal(String line) {
        if (!drives(line)) return null;
        if (lowBattery()) return "battery low (" + battery + "%)";
        if (!lock.allowed()) return "driving is locked: enable it first (web page, tools\\r2.ps1 drive-enable, POST /drive?enable=1)";
        return null;
    }

    /** Checks a command on its way to the controller. Returns false if it was refused. */
    boolean send(String line) {
        long hold = holdFor(line);
        boolean stopping = false;
        try {
            JSONObject c = new JSONObject(line);
            String cmd = c.optString("cmd");
            if ("head-angle".equals(cmd)) {
                int want = c.optInt("angle"), v = clampHead(want);
                if (v != want) line = c.put("angle", v).toString();
                headPos = v;
                headKnown = true;
                headUntil = 0;
            } else if ("head-dir".equals(cmd)) {
                int dir = c.optInt("dir");
                headUntil = dir == 0 ? 0 : System.currentTimeMillis() + Config.HEAD_HOLD_MS;
                if (dir != 0) headKnown = false; // the end-stop sensors stop it; head-shift can't be capped until the next head-angle
            } else if ("head-shift".equals(cmd) && headKnown) {
                int want = c.optInt("angle"), to = clampHead(headPos + want);
                if (to - headPos != want) {
                    if (to == headPos) return true; // already at the limit: nothing to do
                    line = c.put("angle", to - headPos).toString();
                }
                headPos = to;
            } else if ("move".equals(cmd)) {
                if (c.optInt("power") == 0) stopping = true;
            } else if ("mode".equals(cmd)) {
                if (c.optInt("mode", -1) == 0) stopping = true;
            }
        } catch (Exception notJson) {
            // passed through unchanged, like the stock app's raw lines
        }
        if (hold > 0 && lowBattery()) {
            out.log("refused drive command: battery " + battery + "%");
            return false;
        }
        if (hold > 0 && !lock.allowed()) {
            long now = System.currentTimeMillis();
            if (now - lastRefusedNotice >= REFUSED_NOTICE_MS) { // a joystick repeats every few 100 ms
                lastRefusedNotice = now;
                out.log("refused drive command: driving is locked");
                out.onDriveRefused();
            }
            return false;
        }
        boolean ok = out.write(line);
        if (stopping) motionUntil = 0;
        else if (hold > 0 && ok) {
            motionUntil = System.currentTimeMillis() + hold;
            lock.touch();
        }
        return ok;
    }

    /** Every line from the controller; picks battery and charging out of gin reports. */
    void onControllerLine(String line) {
        if (!line.contains("\"gin\"")) return;
        try {
            JSONObject g = new JSONObject(line);
            battery = g.optInt("batt", battery);
            charging = g.optInt("charging-status", g.optInt("charging_status", 0)) != 0;
            watchGin(g);
        } catch (Exception ignored) {
            return;
        }
        if (moving() && lowBattery()) {
            stop("low battery (" + battery + "%)");
            disableDriving("low battery");
        }
    }

    // gin fields whose meaning is known (docs/serial-protocol.md); status and error are not understood yet.
    private static final java.util.Set<String> KNOWN_GIN = new java.util.HashSet<>(java.util.Arrays.asList(
            "cmd", "batt", "charging-status", "charging_status", "arm", "lightsaber", "projector", "head", "mode", "lcd_l", "lcd_s"));
    private final java.util.Map<String, String> ginSeen = new java.util.HashMap<>();

    /**
     * Sensor discovery: the robot has IR, ultrasonic and foot-drop sensors that no known message reports.
     * Logs every change of a gin field we don't understand (status, error, anything new), and whether R2
     * was driving at the time, so a test drive toward a wall or an edge shows what the controller says.
     */
    private void watchGin(JSONObject g) {
        for (java.util.Iterator<String> it = g.keys(); it.hasNext(); ) {
            String k = it.next();
            if (KNOWN_GIN.contains(k)) continue;
            String v = String.valueOf(g.opt(k));
            String was = ginSeen.put(k, v);
            if (was == null ? !"0".equals(v) && !"".equals(v) : !was.equals(v)) {
                EventLog.i("gin " + k + ": " + (was == null ? "" : was + " -> ") + v + (motionUntil != 0 ? " (while driving)" : ""));
            }
        }
    }

    /** Stops driving and the controller's own movement routines. */
    void stop(String why) {
        motionUntil = 0;
        out.write(STOP_MOVE);
        out.write(STOP_MODE);
        if (headUntil != 0) {
            headUntil = 0;
            out.write(STOP_HEAD);
        }
        out.log("stopped: " + why);
    }
}
