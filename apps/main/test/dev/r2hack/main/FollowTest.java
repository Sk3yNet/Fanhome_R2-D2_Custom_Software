package dev.r2hack.main;

import java.util.Random;

/**
 * Off-robot tests for "follow me": the grid sampling, the clothes tracker and the steering, on made-up
 * camera frames. No test framework: run with apps/test.sh.
 */
public final class FollowTest {
    private static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    // ---- made-up frames -------------------------------------------------------------------

    static final int W = 640, H = 480;   // the sensor frame, as R2's camera delivers it (on its side)

    /** A sideways NV21 frame: a speckled grey-green room, and a shirt drawn in upright coordinates. */
    static byte[] frame(long seed, double shirtX, double shirtY, double shirtW, double shirtH, int sy, int su, int sv) {
        byte[] f = new byte[W * H * 3 / 2];
        Random rnd = new Random(seed);
        // Upright picture is H wide and W tall (rot 90: upright col = H-1-sensorY, row = sensorX).
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int col = H - 1 - y, row = x;
                boolean shirt = inBox(col / (double) H, row / (double) W, shirtX, shirtY, shirtW, shirtH);
                f[y * W + x] = (byte) (shirt ? sy : 90 + rnd.nextInt(80));
            }
        }
        int base = W * H;
        for (int y = 0; y < H / 2; y++) {
            for (int x = 0; x < W / 2; x++) {
                int col = H - 1 - y * 2, row = x * 2;
                boolean shirt = inBox(col / (double) H, row / (double) W, shirtX, shirtY, shirtW, shirtH);
                int i = base + y * W + x * 2;
                f[i] = (byte) (shirt ? sv : 110 + rnd.nextInt(30));       // V
                f[i + 1] = (byte) (shirt ? su : 110 + rnd.nextInt(30));   // U
            }
        }
        return f;
    }

    static boolean inBox(double x, double y, double cx, double cy, double w, double h) {
        return Math.abs(x - cx) <= w / 2 && Math.abs(y - cy) <= h / 2;
    }

    /** A red shirt (Y 80, U 100, V 200) at upright centre x, top-to-bottom centre y, width w (height 1.4 w). */
    static byte[] red(long seed, double x, double y, double w) {
        return frame(seed, x, y, w, w * 1.4, 80, 100, 200);
    }

    static final int STEP = 8;

    static int[] cells(byte[] f) {
        return FollowTracker.sample(f, W, H, 90, STEP, null);
    }

    static final int GW = FollowTracker.gridW(W, H, 90, STEP), GH = FollowTracker.gridH(W, H, 90, STEP);

    // ---- tests ----------------------------------------------------------------------------

    static void sampling() {
        check("upright grid is 60 wide", GW == 60);
        check("upright grid is 80 tall", GH == 80);
        // A sensor pixel near (x small, y small) is near the upright top right.
        byte[] f = new byte[W * H * 3 / 2];
        f[4 * W + 4] = (byte) 255;
        int[] c = FollowTracker.sample(f, W, H, 90, STEP, null);
        check("rot 90 puts sensor top-left at upright top-right", c[0 * GW + (GW - 1)] == FollowTracker.bin(255, 0, 0));
        int[] c0 = FollowTracker.sample(f, W, H, 0, STEP, null);
        check("rot 0 leaves it top-left", c0[0] == FollowTracker.bin(255, 0, 0));
        int[] c270 = FollowTracker.sample(f, W, H, 270, STEP, null);
        int gw270 = FollowTracker.gridW(W, H, 270, STEP), gh270 = FollowTracker.gridH(W, H, 270, STEP);
        check("rot 270 puts it bottom-left", c270[(gh270 - 1) * gw270] == FollowTracker.bin(255, 0, 0));
    }

    /** Face at upright (0.5, 0.25), 0.12 of the width: the shirt under it is at y about 0.25 + 0.56. */
    static FollowTracker lockedTracker() {
        FollowTracker t = new FollowTracker();
        boolean ok = t.lock(cells(red(1, 0.5, 0.55, 0.4)), GW, GH, 0.5, 0.25, 0.12);
        check("locks onto a red shirt under a face", ok);
        return t;
    }

    static void tracking() {
        FollowTracker t = lockedTracker();
        FollowTracker.Result r = t.track(cells(red(2, 0.5, 0.55, 0.4)), GW, GH, 0);
        check("finds the shirt where it was: " + r, r.found && Math.abs(r.x) < 0.1 && Math.abs(r.size - 1) < 0.2);

        // Walks to the right a little at a time.
        double x = 0.5;
        for (int i = 0; i < 6; i++) {
            x += 0.05;
            r = t.track(cells(red(3 + i, x, 0.55, 0.4)), GW, GH, i);
        }
        check("follows it to the right: " + r, r.found && r.x > 0.4 && r.x < 0.75);

        // Walks away: the shirt gets narrower.
        double w = 0.4;
        for (int i = 0; i < 6; i++) {
            w -= 0.035;
            r = t.track(cells(red(20 + i, x, 0.5, w)), GW, GH, i);
        }
        check("sees it get smaller (further away): " + r, r.found && r.size < 0.75);

        // Comes close: wider, and running off the bottom of the picture.
        for (int i = 0; i < 8; i++) {
            w += 0.07;
            r = t.track(cells(frame(40 + i, 0.5, 0.75, w, 0.8, 80, 100, 200)), GW, GH, i);
        }
        check("sees it get bigger (closer), even cut off at the bottom: " + r, r.found && r.size > 1.1);

        // Gone.
        r = t.track(cells(frame(60, 0, 0, 0, 0, 0, 0, 0)), GW, GH, 0);
        check("an empty room is lost", !r.found);
        // Back somewhere else: needs two frames in a row before it counts.
        r = t.track(cells(red(61, 0.3, 0.55, 0.35)), GW, GH, 0);
        check("not trusted after one frame", !r.found);
        r = t.track(cells(red(62, 0.3, 0.55, 0.35)), GW, GH, 0);
        check("found again after two: " + r, r.found && r.x < -0.2 && r.x > -0.6);
    }

    static void lockRefusals() {
        FollowTracker t = new FollowTracker();
        // Nothing under the face that isn't everywhere else in the room.
        byte[] f = frame(70, 0, 0, 0, 0, 0, 0, 0);
        boolean ok = t.lock(cells(f), GW, GH, 0.5, 0.25, 0.12);
        check("refuses clothes that look like the room (" + t.problem() + ")", !ok && !t.locked());
        check("refuses a face at the bottom edge", !t.lock(cells(red(71, 0.5, 0.55, 0.4)), GW, GH, 0.5, 0.99, 0.12));
        // A failed lock keeps a good earlier one.
        FollowTracker good = lockedTracker();
        good.lock(cells(f), GW, GH, 0.5, 0.25, 0.12);
        FollowTracker.Result r = good.track(cells(red(72, 0.5, 0.55, 0.4)), GW, GH, 0);
        check("a failed lock leaves the old one working", good.locked() && r.found);
    }

    static void follower() {
        Follower fo = new Follower();
        fo.onFrame(red(80, 0.5, 0.55, 0.4), W, H, 0);
        check("off: ignores frames", fo.latest() == null && !fo.locked());
        fo.start();
        fo.onFrame(red(81, 0.5, 0.55, 0.4), W, H, 1000);
        check("no face yet: not locked", !fo.locked());
        fo.onFace(90, 0.5, 0.25, 0.12);
        fo.onFrame(red(82, 0.5, 0.55, 0.4), W, H, 1100);
        check("locks on the frame after a face", fo.locked());
        fo.onFrame(red(83, 0.55, 0.55, 0.4), W, H, 1300);
        FollowTracker.Result r = fo.latest();
        check("tracks after locking: " + r, r != null && r.found && r.time == 1300);
        fo.onFrame(red(84, 0.6, 0.55, 0.4), W, H, 1350);
        check("skips frames that come too fast", fo.latest().time == 1300);
        fo.stop();
        check("stop forgets it", !fo.locked() && fo.latest() == null && !fo.active());
    }

    static FollowTracker.Result at(double x, double size, long t) {
        return new FollowTracker.Result(true, x, size, 1, t);
    }

    static void control() {
        FollowControl c = new FollowControl(0);
        FollowControl.Command m = c.update(at(0, 1, 0), 0);
        check("as close as at the start: stays put", !m.moving() && !m.done);
        m = c.update(at(0, 0.6, 200), 200);
        check("further away: drives forward: " + m, m.moving() && m.angle == 0 && m.power >= FollowControl.MIN_POWER
                && m.power <= FollowControl.MAX_POWER);
        m = c.update(at(0, 0.85, 400), 400);
        check("keeps driving until nearly back to the start size: " + m, m.moving());
        m = c.update(at(0, 0.97, 600), 600);
        check("stops once close enough", !m.moving());
        m = c.update(at(0, 0.85, 800), 800);
        check("doesn't restart for a small gap", !m.moving());
        m = c.update(at(0.25, 0.7, 1000), 1000);
        check("steers right gently while driving: " + m, m.moving() && m.angle > 0 && m.angle <= FollowControl.MAX_STEER);
        m = c.update(at(-0.9, 0.7, 1200), 1200);
        check("far to the left: turns on the spot: " + m, m.power == FollowControl.SPIN_POWER && m.angle == -90);
        m = c.update(at(-0.5, 0.7, 1400), 1400);
        check("keeps turning until nearly centred", m.angle == -90);
        m = c.update(at(-0.2, 0.7, 1600), 1600);
        check("then drives again: " + m, m.moving() && m.angle > -90 && m.angle < 0);
        m = c.update(at(0, 2.5, 1800), 1800);
        check("very close: stops, never reverses", !m.moving() && m.angle != 180);
        for (int i = 0; i < 50; i++) {
            m = c.update(at((i % 3 - 1) * 0.8, 0.3 + i * 0.05, 2000 + i * 200), 2000 + i * 200);
            check("never reverses (" + m + ")", m.angle != 180 && m.power >= 0 && m.power <= FollowControl.MAX_POWER);
        }

        c = new FollowControl(0);
        m = c.update(at(0, 0.5, 0), 0);
        m = c.update(at(0, 0.5, 0), 1000);
        check("stale camera result: stops", !m.moving() && m.why.contains("camera"));
        m = c.update(new FollowTracker.Result(false, 0, 0, 0, 1200), 1200);
        check("lost: stops and waits", !m.moving() && !m.done && c.lost());
        m = c.update(new FollowTracker.Result(false, 0, 0, 0, 25_000), 25_000);
        check("still waiting at 24 s", !m.done);
        m = c.update(at(0, 0.6, 26_000), 26_000);
        check("found again: drives", m.moving() && !c.lost());
        m = c.update(null, 27_000);
        m = c.update(null, 27_000 + FollowControl.GIVE_UP_MS + 1);
        check("gives up after being lost too long", m.done);

        c = new FollowControl(0);
        m = c.update(at(0, 0.5, FollowControl.MAX_MS + 1), FollowControl.MAX_MS + 1);
        check("ends after the time limit", m.done);
    }

    public static void main(String[] args) {
        sampling();
        tracking();
        lockRefusals();
        follower();
        control();
        System.out.println("follow me: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
