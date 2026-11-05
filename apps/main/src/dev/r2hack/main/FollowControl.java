package dev.r2hack.main;

/**
 * Turns where the followed person is (FollowTracker.Result) into a drive command, a few times a second.
 *
 * R2 has no obstacle sensors the main board can read, so this is careful by design:
 *  - it never reverses, and stops when the person is as close as when it locked on, or closer;
 *  - it drives slowly (power at most MAX_POWER) and steers gently while driving;
 *  - when the person is well off to one side it turns on the spot instead of driving;
 *  - when the person is lost, or the camera stops sending results, it stops at once and waits, and ends
 *    "follow me" after GIVE_UP_MS without seeing them;
 *  - it ends after MAX_MS in any case.
 * The agent's motion watchdog stops R2 as well if these commands stop coming (Config.MOVE_HOLD_MS).
 *
 * The numbers are first guesses from docs/serial-protocol.md (move power 50 is about 3 inches a second,
 * angle 90 / -90 turn on the spot, small angles curve) and need tuning on the robot.
 * Plain Java, tested off the robot by FollowTest.
 */
final class FollowControl {
    static final class Command {
        final int power, angle;
        final boolean done;
        final String why;

        Command(int power, int angle, boolean done, String why) {
            this.power = power;
            this.angle = angle;
            this.done = done;
            this.why = why;
        }

        boolean moving() {
            return power != 0;
        }

        @Override
        public String toString() {
            return done ? "done: " + why : power == 0 ? "stop: " + why : "move power " + power + " angle " + angle + ": " + why;
        }
    }

    static final double HALF_VIEW_DEG = 20;       // the upright picture is about 40 degrees across (as FaceTracker assumes)
    static final double SPIN_START_DEG = 14, SPIN_END_DEG = 6;
    static final int SPIN_POWER = 25;
    static final double DRIVE_START = 0.8, DRIVE_STOP = 0.95;  // size compared with when it locked on
    static final int MIN_POWER = 30, MAX_POWER = 50;
    static final double POWER_GAIN = 100;          // extra power for each 0.1 of size below 1: 10
    static final double STEER_GAIN = 1.5;          // move angle per degree off centre while driving
    static final int MAX_STEER = 35;
    static final long STALE_MS = 800;              // a result older than this means the camera stalled
    static final long GIVE_UP_MS = 30_000;
    static final long MAX_MS = 15 * 60_000L;

    private final long started;
    private long lostSince;
    private boolean driving, spinning;
    private int spinDir;

    FollowControl(long now) {
        started = now;
    }

    /** True while R2 is waiting for a lost person to show up again. */
    boolean lost() {
        return lostSince != 0;
    }

    Command update(FollowTracker.Result r, long now) {
        if (now - started > MAX_MS) return done("followed for " + MAX_MS / 60_000 + " minutes");
        boolean fresh = r != null && now - r.time <= STALE_MS;
        if (!fresh || !r.found) {
            driving = false;
            spinning = false;
            if (lostSince == 0) lostSince = now;
            if (now - lostSince > GIVE_UP_MS) return done("lost for " + GIVE_UP_MS / 1000 + " s");
            return stop(fresh ? "lost" : "no camera result");
        }
        lostSince = 0;
        double bearing = r.x * HALF_VIEW_DEG;
        double off = Math.abs(bearing);

        // Well off to one side: turn on the spot toward them, until they're nearly straight ahead.
        if (spinning ? off > SPIN_END_DEG : off > SPIN_START_DEG) {
            int dir = bearing > 0 ? 1 : -1;
            if (spinning && dir != spinDir) {      // overshot: stop for a moment rather than swing back and forth
                spinning = false;
                return stop("overshot the turn");
            }
            spinning = true;
            spinDir = dir;
            driving = false;
            return new Command(SPIN_POWER, 90 * dir, false, String.format(java.util.Locale.US, "turning toward them (%.0f deg)", bearing));
        }
        spinning = false;

        if (driving ? r.size < DRIVE_STOP : r.size < DRIVE_START) {
            driving = true;
            int power = (int) Math.round(MIN_POWER + (1 - r.size) * POWER_GAIN);
            power = Math.max(MIN_POWER, Math.min(MAX_POWER, power));
            int angle = (int) Math.round(bearing * STEER_GAIN);
            angle = Math.max(-MAX_STEER, Math.min(MAX_STEER, angle));
            return new Command(power, angle, false, String.format(java.util.Locale.US, "following (size %.2f)", r.size));
        }
        driving = false;
        return stop(String.format(java.util.Locale.US, "close enough (size %.2f)", r.size));
    }

    private static Command stop(String why) {
        return new Command(0, 0, false, why);
    }

    private static Command done(String why) {
        return new Command(0, 0, true, why);
    }
}
