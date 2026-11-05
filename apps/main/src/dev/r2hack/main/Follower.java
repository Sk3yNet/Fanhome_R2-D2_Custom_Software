package dev.r2hack.main;

/**
 * The camera side of "follow me": gets camera frames and face sightings, and keeps the latest
 * FollowTracker.Result for the drive loop (Brain's "follow" action, steered by FollowControl).
 *
 * Starting it waits for a face (from FaceTracker). The next frame after a face is seen locks onto the
 * clothes under it, in the orientation the face was found in. If the person is lost and a face shows up
 * again (they turned round to look), it locks onto them afresh.
 * Frames arrive on the camera thread and faces on the face-detection thread; the drive loop reads
 * latest() from the action thread, so everything here is synchronized. Plain Java, tested by FollowTest.
 */
final class Follower {
    private static final long FRAME_MS = 150;   // about 6 tracking updates a second
    private static final int CELLS_ACROSS = 80; // grid cells along the sensor's long side

    private boolean active;
    private int rot = 90;                       // R2's camera is on its side (see FaceTracker)
    private double[] pendingFace;               // {x, y, width, rot} waiting for the next frame
    private final FollowTracker tracker = new FollowTracker();
    private FollowTracker.Result latest;
    private int[] cells;
    private long lastFrame;
    private String note = "off";
    private int lockFailures;

    synchronized void start() {
        active = true;
        tracker.reset();
        latest = null;
        pendingFace = null;
        lockFailures = 0;
        note = "waiting for a face";
    }

    synchronized void stop() {
        active = false;
        tracker.reset();
        latest = null;
        pendingFace = null;
        note = "off";
    }

    synchronized boolean active() {
        return active;
    }

    synchronized boolean locked() {
        return active && tracker.locked();
    }

    /** How many times locking failed since start, e.g. because the clothes match the room. */
    synchronized int lockFailures() {
        return lockFailures;
    }

    synchronized FollowTracker.Result latest() {
        return latest;
    }

    synchronized String status() {
        if (!active) return "off";
        return note + (latest != null && tracker.locked() ? " (" + latest + ")" : "");
    }

    /**
     * A face seen by FaceTracker: rot is the quarter turn it was found in; x, y (its middle) and width are
     * fractions of the turned picture. Used to lock on, or to lock on again once the person is lost.
     */
    synchronized void onFace(int rot, double x, double y, double width) {
        if (!active) return;
        boolean needLock = !tracker.locked() || latest == null || !latest.found;
        if (!needLock) return;
        pendingFace = new double[] {x, y, width, rot};
    }

    /** A camera frame (NV21, as CameraStream.RawListener gets it). It must return quickly. */
    void onFrame(byte[] nv21, int w, int h) {
        onFrame(nv21, w, h, System.currentTimeMillis());
    }

    synchronized void onFrame(byte[] nv21, int w, int h, long now) {
        if (!active) return;
        if (now - lastFrame < FRAME_MS && pendingFace == null) return;
        lastFrame = now;
        int step = Math.max(2, Math.max(w, h) / CELLS_ACROSS);
        double[] f = pendingFace;
        pendingFace = null;
        int useRot = f != null ? (int) f[3] : rot;
        int gw = FollowTracker.gridW(w, h, useRot, step), gh = FollowTracker.gridH(w, h, useRot, step);
        cells = FollowTracker.sample(nv21, w, h, useRot, step, cells);
        if (f != null) {
            boolean relock = tracker.locked();
            if (tracker.lock(cells, gw, gh, f[0], f[1], f[2])) {
                rot = useRot;
                note = relock ? "found them again" : "following";
            } else {
                lockFailures++;
                note = "can't lock on: " + tracker.problem();
                return; // keep the old lock (if any) and keep looking
            }
        }
        if (!tracker.locked()) return;
        latest = tracker.track(cells, gw, gh, now);
        if (latest.found) note = "following";
        else if (!note.startsWith("can't")) note = "lost them, waiting";
    }
}
