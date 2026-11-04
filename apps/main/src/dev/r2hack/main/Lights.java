package dev.r2hack.main;

/**
 * R2's status lights, as the factory software shows them. The front light has red and blue LEDs, the
 * back light green and yellow; each LED is set with the controller's `led` command:
 * 0 leave as is, 1 off, 2 on, 3/4 slow flash (two phases), 5/6 fast flash (two phases).
 * The front shows what R2 is doing (ready, patrol, pairing, charging, low battery, sleep); the back shows
 * the network (home Wi-Fi, no Wi-Fi, hotspot, sleeping), or that R2 is listening for a voice command. Events can show a pattern briefly, then the base
 * pattern comes back.
 */
final class Lights {
    interface Output {
        void led(int r, int b, int y, int g);
    }

    // Front patterns: {r, b}
    static final int[] READY = {2, 2}, PAIR = {1, 3}, POWER_OFF = {5, 1}, LOW_BATTERY = {2, 1}, SLEEP = {1, 1},
            CHARGING = {3, 3}, CHARGED = {1, 2}, PATROL = {3, 4}, PAIR_FAILED = {3, 1}, CONNECTING = {1, 5},
            FACE = {1, 2};
    // Back patterns: {g, y}
    static final int[] HOME_WIFI = {2, 1}, NO_WIFI = {5, 1}, HOTSPOT = {1, 2}, HOTSPOT_CONNECTING = {1, 5},
            SLEEP_HOTSPOT = {1, 3}, SLEEP_WIFI = {3, 1}, LISTENING = {3, 4};

    private final Robot robot;
    private final Output out;
    private int[] front, back;
    private int[] frontOverride;

    Lights(Robot robot, Output out) {
        this.robot = robot;
        this.out = out;
    }

    /** Forgets what was last sent, so the next refresh() sends everything (e.g. after reconnecting). */
    synchronized void reset() {
        front = null;
        back = null;
    }

    /** Shows the base patterns for the robot's current state. */
    synchronized void refresh() {
        show(frontOverride != null ? frontOverride : frontBase(), backBase());
    }

    /** Shows a front pattern over the base one until clear() (e.g. connecting to Wi-Fi). */
    synchronized void override(int[] pattern) {
        frontOverride = pattern;
        refresh();
    }

    synchronized void clear() {
        frontOverride = null;
        refresh();
    }

    /** A front pattern for a moment (e.g. pairing failed), then back to normal. */
    void flash(final int[] pattern, final long ms) {
        override(pattern);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                }
                synchronized (Lights.this) {
                    if (frontOverride == pattern) clear();
                }
            }
        }, "lights-flash").start();
    }

    private int[] frontBase() {
        if (robot.mode == Robot.PATROL) return PATROL;
        if (robot.faceSeen) return FACE;
        if (robot.mode == Robot.PAIR) return PAIR;
        if (robot.charging == 1) return CHARGING;
        if (robot.charging == 2) return CHARGED;
        if (robot.battery >= 0 && robot.battery <= Robot.LOW_BATTERY_PCT) return LOW_BATTERY;
        return robot.mode == Robot.SLEEP ? SLEEP : READY;
    }

    private int[] backBase() {
        if (robot.mode == Robot.SLEEP) return robot.apMode ? SLEEP_HOTSPOT : SLEEP_WIFI;
        if (robot.listening) return LISTENING;
        if (robot.apMode) return HOTSPOT;
        return robot.ssid.isEmpty() ? NO_WIFI : HOME_WIFI;
    }

    private void show(int[] f, int[] b) {
        boolean frontSame = f == front, backSame = b == back;
        if (frontSame && backSame) return;
        front = f;
        back = b;
        // 0 = leave unchanged, so only the half that changed is sent.
        android.util.Log.i("R2Lights", "front " + (frontSame ? "same" : f[0] + "/" + f[1]) + ", back " + (backSame ? "same" : b[0] + "/" + b[1]));
        out.led(frontSame ? 0 : f[0], frontSame ? 0 : f[1], backSame ? 0 : b[1], backSame ? 0 : b[0]);
    }
}
