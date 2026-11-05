package dev.r2hack.agent;

import java.util.ArrayList;
import java.util.List;

/** Off-robot tests for the drive lock and how MotionGuard applies it. No test framework: run with apps/test.sh. */
public final class DriveLockTest {
    private static int passed, failed;

    static final class FakeClock implements DriveLock.Clock {
        long now = 1_000_000;

        public long now() {
            return now;
        }
    }

    static final class FakeOut implements MotionGuard.Output {
        final List<String> written = new ArrayList<>();
        int changed, refused;

        public boolean write(String line) {
            written.add(line);
            return true;
        }

        public void log(String msg) {
        }

        public void onDriveChanged() {
            changed++;
        }

        public void onDriveRefused() {
            refused++;
        }
    }

    static final String MOVE = "{\"cmd\":\"move\",\"power\":40,\"angle\":0}";
    static final String TURN = "{\"cmd\":\"mode\",\"mode\":3}";
    static final String PATROL = "{\"cmd\":\"mode\",\"mode\":9}";
    static final String HEAD = "{\"cmd\":\"head-angle\",\"angle\":30}";
    static final String LED = "{\"cmd\":\"led\",\"r\":1}";

    public static void main(String[] args) {
        FakeClock clock = new FakeClock();

        // The lock itself.
        DriveLock off = new DriveLock(false, clock);
        check("lock off: driving allowed", off.allowed() && "off".equals(off.state()));
        DriveLock l = new DriveLock(true, clock);
        check("lock on: locked at start", !l.allowed() && "locked".equals(l.state()));
        l.enable(10);
        check("enabled", l.allowed() && l.enabled() && "enabled".equals(l.state()));
        check("seconds left", l.secondsLeft() == 600);
        clock.now += 9 * 60_000;
        l.touch();
        clock.now += 9 * 60_000;
        check("driving keeps it enabled", l.allowed() && !l.expire());
        clock.now += 60_001;
        check("idle too long: locked", !l.allowed());
        check("expire reports it once", l.expire() && !l.expire());
        l.enable(1);
        l.setRequired(false);
        l.setRequired(true);
        check("switching the lock on again drops the enable", !l.allowed());
        check("minutes are checked", throwsOn(l, 0) && throwsOn(l, DriveLock.MAX_MINUTES + 1));

        // MotionGuard with the lock on.
        FakeOut out = new FakeOut();
        MotionGuard g = new MotionGuard(out, new DriveLock(true, clock));
        check("move refused while locked", !g.send(MOVE) && out.written.isEmpty());
        check("the main app hears about it", out.refused == 1);
        check("repeats don't flood it", !g.send(MOVE) && out.refused == 1);
        check("controller turn refused", !g.send(TURN));
        check("patrol refused", !g.send(PATROL));
        check("refusal says why", g.refusal(MOVE) != null && g.refusal(MOVE).contains("locked"));
        check("head moves still allowed", g.send(HEAD) && g.refusal(HEAD) == null);
        check("lights still allowed", g.send(LED));
        check("stop always allowed", g.send(MotionGuard.STOP_MOVE) && g.send(MotionGuard.STOP_MODE));
        check("not moving", !g.moving());

        g.enableDriving(5);
        check("enable is announced", out.changed == 1);
        out.written.clear();
        check("move goes out once enabled", g.send(MOVE) && out.written.contains(MOVE) && g.moving());

        g.disableDriving("test");
        check("disabling while moving stops R2", out.written.contains(MotionGuard.STOP_MOVE) && !g.moving());
        check("disable is announced", out.changed == 2);
        check("move refused again", !g.send(MOVE));

        // The enable runs out on its own.
        g.enableDriving(1);
        int before = out.changed;
        clock.now += 61_000;
        g.tick();
        check("timeout locks and is announced", !g.driveLock().allowed() && out.changed == before + 1);

        // Switching the lock off lets everything drive; on again stops R2.
        g.setLockRequired(false);
        out.written.clear();
        check("lock off: move goes out", g.send(MOVE) && g.moving());
        g.setLockRequired(true);
        check("lock on while moving: stopped", out.written.contains(MotionGuard.STOP_MOVE) && !g.moving());
        check("drives(): head and stop don't count", !MotionGuard.drives(HEAD) && !MotionGuard.drives(MotionGuard.STOP_MOVE));

        // Low battery still wins over an enable.
        FakeOut out2 = new FakeOut();
        MotionGuard g2 = new MotionGuard(out2, new DriveLock(true, clock));
        g2.enableDriving(5);
        g2.onControllerLine("{\"cmd\":\"gin\",\"batt\":5}");
        check("low battery refuses even when enabled", !g2.send(MOVE) && g2.refusal(MOVE).contains("battery"));

        System.out.println("DriveLockTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static boolean throwsOn(DriveLock l, int minutes) {
        try {
            l.enable(minutes);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }
}
