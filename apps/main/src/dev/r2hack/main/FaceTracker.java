package dev.r2hack.main;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Face detection with Android's built-in detector (no extra libraries, light enough for this board).
 * Like the factory software: when a face has been seen for 1.5 s it plays a sound and shows the face
 * light, and the head turns toward it in small steps; 1.5 s without a face ends tracking.
 *
 * R2's camera is mounted on its side, so while no face is in view each frame tries the next orientation
 * (a quarter turn either way, or upright); once a face shows up it stays with that orientation.
 */
final class FaceTracker implements CameraStream.RawListener {
    interface Host {
        void onFaceTracking(boolean tracking);

        /** Turns the head by a small relative angle (the controller's head-shift). */
        void shiftHead(int degrees);

        /**
         * Every confident face, confirmed or not (for "follow me"): rot is the quarter turn the picture was
         * given, x and y the face's middle and width its width, as fractions of the turned picture's size.
         */
        void onFaceSeen(int rot, double x, double y, double width);
    }

    private static final String TAG = "R2Face";
    private static final long FRAME_MS = 400;          // about 2.5 detections a second
    private static final long CONFIRM_MS = 1500, LOST_MS = 1500;
    private static final int SCALE = 2;                 // 640x480 -> 320x240
    private static final double MAX_DEGREE = 40, FOLLOW = 0.5;
    private static final int MAX_STEP = 5;

    private final Host host;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean enabled;
    private long lastFrame, firstSeen, lastSeen;
    private boolean tracking;
    /** Quarter turns (clockwise) to try; R2's camera is mounted on its side. */
    private static final int[] ROTATIONS = {90, 270, 0};
    private int rotIndex;
    private boolean first = true;
    private volatile int[] pixels;
    private byte[] grey;

    FaceTracker(Host host) {
        this.host = host;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) stopTracking();
    }

    boolean tracking() {
        return tracking;
    }

    @Override
    public void onFrame(byte[] nv21, final int w, final int h) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastFrame < FRAME_MS || !busy.compareAndSet(false, true)) return;
        lastFrame = now;
        // The camera reuses its buffers, so keep our own copy of the grey (Y) plane.
        byte[] y = grey;
        if (y == null || y.length != w * h) grey = y = new byte[w * h];
        System.arraycopy(nv21, 0, y, 0, w * h);
        final byte[] frame = y;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    detect(frame, w, h);
                } catch (Throwable e) {
                    Log.w(TAG, "face detection failed", e);
                } finally {
                    busy.set(false);
                }
            }
        }, "face-detect");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void detect(byte[] nv21, int w, int h) {
        // While nothing is in view, each frame tries the next orientation; once a face shows up, stay with it.
        if (!tracking && firstSeen == 0 && !first) rotIndex = (rotIndex + 1) % ROTATIONS.length;
        first = false;
        int rot = ROTATIONS[rotIndex];
        int sw = (w / SCALE) & ~1, sh = (h / SCALE) & ~1;      // FaceDetector needs an even width
        boolean turned = rot != 0;
        int bw = turned ? sh : sw, bh = turned ? sw : sh;
        int[] px = pixels;
        if (px == null || px.length != bw * bh) pixels = px = new int[bw * bh];
        // Grey from the Y plane, scaled down and (if needed) turned a quarter turn.
        for (int y = 0; y < sh; y++) {
            int row = y * SCALE * w;
            for (int x = 0; x < sw; x++) {
                int v = nv21[row + x * SCALE] & 0xff;
                int i = rot == 90 ? x * bw + (sh - 1 - y)            // clockwise
                        : rot == 270 ? (sw - 1 - x) * bw + y         // counter-clockwise
                        : y * bw + x;
                px[i] = 0xff000000 | (v << 16) | (v << 8) | v;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.RGB_565);
        FaceDetector.Face[] faces = new FaceDetector.Face[3];
        int n = new FaceDetector(bw, bh, faces.length).findFaces(bmp, faces);
        bmp.recycle();
        long now = System.currentTimeMillis();
        FaceDetector.Face best = null;
        for (int i = 0; i < n; i++) if (best == null || faces[i].eyesDistance() > best.eyesDistance()) best = faces[i];
        if (best == null || best.confidence() < 0.4f) {
            if (tracking && now - lastSeen > LOST_MS) stopTracking();
            if (!tracking) firstSeen = 0;
            return;
        }
        lastSeen = now;
        PointF mid = new PointF();
        best.getMidPoint(mid);
        host.onFaceSeen(rot, mid.x / bw, mid.y / bh, 2.0 * best.eyesDistance() / bw);
        if (firstSeen == 0) {
            firstSeen = now;
            if (!tracking) Log.i(TAG, "face in view (turned " + rot + "), confirming");
        }
        if (!tracking && now - firstSeen >= CONFIRM_MS) {
            tracking = true;
            Log.i(TAG, "tracking a face (camera image turned " + rot + " degrees clockwise)");
            host.onFaceTracking(true);
        }
        if (tracking) follow(best, bw);
    }

    /** The factory's head following: half of the face's offset, at most 5 degrees a step. */
    private void follow(FaceDetector.Face face, int width) {
        PointF mid = new PointF();
        face.getMidPoint(mid);
        double target = (mid.x - width / 2.0) / width * MAX_DEGREE;
        int step = (int) (FOLLOW * target);
        if (Math.abs(target) > 2 && step != 0) host.shiftHead(Math.max(-MAX_STEP, Math.min(MAX_STEP, step)));
    }

    private void stopTracking() {
        firstSeen = 0;
        if (tracking) {
            tracking = false;
            host.onFaceTracking(false);
        }
    }
}
