package dev.r2hack.agent;

import org.json.JSONObject;

/**
 * Safety layer on every command headed for the controller, whoever sent it:
 *  - motion watchdog: a drive command only lasts a few seconds unless it is repeated, then the
 *    agent sends stop itself, so a hung or crashed app (or a lost Wi-Fi client) can't leave R2 driving;
 *  - head watchdog: a continuous head turn (head-dir) stops unless it is repeated;
 *  - low battery: below Config.LOW_BATTERY_PCT, new drive commands are refused and driving is stopped.
 * Lives in the agent so it keeps working when the main app is hung.
 */
final class MotionGuard {
    interface Output {
        boolean write(String line);
    }

    static final String STOP_MOVE = "{\"cmd\":\"move\",\"power\":0,\"angle\":0}";
    static final String STOP_MODE = "{\"cmd\":\"mode\",\"mode\":0}";
    static final String STOP_HEAD = "{\"cmd\":\"head-dir\",\"dir\":0}";

    private final Output out;
    private volatile long motionUntil;   // 0 = not moving
    private volatile long headUntil;     // 0 = head not turning (head-dir)
    private volatile int battery = -1;   // from gin, -1 until the first report
    private volatile boolean charging;

    MotionGuard(Output out) {
        this.out = out;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    long until = motionUntil;
                    if (until != 0 && System.currentTimeMillis() > until) stop("motion watchdog: no new drive command");
                    long head = headUntil;
                    if (head != 0 && System.currentTimeMillis() > head) {
                        headUntil = 0;
                        out.write(STOP_HEAD);
                    }
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

    /** Checks a command on its way to the controller. Returns false if it was refused. */
    boolean send(String line) {
        long hold = 0;
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
                else hold = Config.MOVE_HOLD_MS;
            } else if ("mode".equals(cmd)) {
                int m = c.optInt("mode", -1);
                if (m == 0) stopping = true;
                else if (m == 9) hold = Config.PATROL_HOLD_MS;
                else if (m == 2 || m == 3 || m == 4 || m == 5 || m == 12) hold = Config.MODE_HOLD_MS;
            }
        } catch (Exception notJson) {
            // passed through unchanged, like the stock app's raw lines
        }
        if (hold > 0 && lowBattery()) {
            EventLog.i("refused drive command: battery " + battery + "%");
            return false;
        }
        boolean ok = out.write(line);
        if (stopping) motionUntil = 0;
        else if (hold > 0 && ok) motionUntil = System.currentTimeMillis() + hold;
        return ok;
    }

    /** Every line from the controller; picks battery and charging out of gin reports. */
    void onControllerLine(String line) {
        if (!line.contains("\"gin\"")) return;
        try {
            JSONObject g = new JSONObject(line);
            battery = g.optInt("batt", battery);
            charging = g.optInt("charging-status", g.optInt("charging_status", 0)) != 0;
        } catch (Exception ignored) {
            return;
        }
        if (moving() && lowBattery()) stop("low battery (" + battery + "%)");
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
        EventLog.i("stopped: " + why);
    }
}
