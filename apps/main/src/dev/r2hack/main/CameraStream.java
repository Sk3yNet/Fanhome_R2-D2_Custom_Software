package dev.r2hack.main;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;

/**
 * R2's camera as MJPEG. Listens on 127.0.0.1 only: the agent serves it to the network at
 * /camera.mjpg and /camera.jpg after checking the token, so there is one place that decides who
 * may watch. The camera is only open while someone is watching.
 */
final class CameraStream {
    static final int PORT = 7711;
    private static final String TAG = "R2Camera";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String BOUNDARY = "r2frame";
    private static final int WANT_W = 640, WANT_H = 480, QUALITY = 60;
    private static final long FRAME_MS = 100;          // at most 10 fps, to spare the CPU
    private static final long CLOSE_AFTER_MS = 3000;   // keep it open briefly between viewers

    private final Object frameLock = new Object();
    private byte[] frame;                              // latest JPEG as the camera delivers it (phone app)
    private byte[] upright;                            // latest JPEG turned upright (web, Home Assistant)
    private long frameNo, uprightNo;                   // count of each kind of frame
    private int viewers;                               // everyone watching
    private int webViewers;                            // of those, the ones that get the upright picture
    private byte[] turned;                             // reused buffer for the turned frame
    private Camera camera;
    private SurfaceTexture texture;
    private Handler handler;
    private long lastEncode;
    private volatile String problem;

    private final Runnable closeIfIdle = new Runnable() {
        @Override
        public void run() {
            synchronized (frameLock) {
                if (viewers > 0 || detecting) return;
            }
            close();
        }
    };

    void start() {
        HandlerThread ht = new HandlerThread("camera");
        ht.start();
        handler = new Handler(ht.getLooper()); // camera callbacks arrive on this thread
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "camera-http");
        t.setDaemon(true);
        t.start();
    }

    String status() {
        synchronized (frameLock) {
            if (problem != null) return "camera: " + problem;
            return "camera: " + (viewers > 0 ? viewers + " watching" : detecting ? "on for face detection" : "off");
        }
    }

    // ---- in-process viewers (the phone app's video) -----------------------------

    void addViewer() {
        synchronized (frameLock) {
            viewers++;
        }
        open();
    }

    void removeViewer() {
        synchronized (frameLock) {
            viewers--;
            if (viewers == 0) handler.postDelayed(closeIfIdle, CLOSE_AFTER_MS);
        }
    }

    /** Waits for a frame newer than seen[0] (-1: the next one) and updates seen[0]. Null on timeout. */
    byte[] next(long[] seen, long timeoutMs) throws InterruptedException {
        synchronized (frameLock) {
            byte[] f = nextFrameLocked(seen[0], timeoutMs, false);
            seen[0] = frameNo;
            return f;
        }
    }

    // ---- camera ------------------------------------------------------------

    private void open() {
        handler.removeCallbacks(closeIfIdle);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) return;
                try {
                    if (Camera.getNumberOfCameras() == 0) throw new Exception("no camera found");
                    Camera c = Camera.open(0);
                    Camera.Parameters p = c.getParameters();
                    Camera.Size best = pickSize(p.getSupportedPreviewSizes());
                    if (best != null) p.setPreviewSize(best.width, best.height);
                    p.setPreviewFormat(ImageFormat.NV21);
                    c.setParameters(p);
                    texture = new SurfaceTexture(10); // no screen needed; kept so it isn't garbage collected
                    c.setPreviewTexture(texture);
                    final Camera.Size size = c.getParameters().getPreviewSize();
                    // Reuse a few frame buffers instead of a new one per frame.
                    int bytes = size.width * size.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
                    for (int i = 0; i < 3; i++) c.addCallbackBuffer(new byte[bytes]);
                    c.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                        @Override
                        public void onPreviewFrame(byte[] data, Camera cam) {
                            try {
                                encode(data, size.width, size.height);
                            } finally {
                                cam.addCallbackBuffer(data);
                            }
                        }
                    });
                    c.startPreview();
                    camera = c;
                    problem = null;
                    Log.i(TAG, "camera on, " + size.width + "x" + size.height);
                } catch (Exception e) {
                    problem = "unavailable (" + e.getMessage() + ")";
                    Log.w(TAG, "camera open failed", e);
                }
            }
        });
    }

    private void close() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera == null) return;
                try {
                    camera.setPreviewCallbackWithBuffer(null);
                    camera.stopPreview();
                } catch (Exception ignored) {
                }
                camera.release();
                camera = null;
                if (texture != null) texture.release();
                texture = null;
                Log.i(TAG, "camera off");
            }
        });
    }

    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (best == null || Math.abs(s.width * s.height - WANT_W * WANT_H) < Math.abs(best.width * best.height - WANT_W * WANT_H)) {
                best = s;
            }
        }
        return best;
    }

    /** Gets raw frames (NV21) as they arrive, for face detection. It must return quickly. */
    interface RawListener {
        void onFrame(byte[] nv21, int width, int height);
    }

    private volatile RawListener rawListener;

    private volatile boolean detecting;

    /** Keeps the camera on for face detection (frames go to the RawListener), or lets it turn off. */
    void setDetecting(boolean on) {
        synchronized (frameLock) {
            if (detecting == on) return;
            detecting = on;
            if (!on && viewers == 0) handler.postDelayed(closeIfIdle, CLOSE_AFTER_MS);
        }
        if (on) open();
    }

    void setRawListener(RawListener l) {
        rawListener = l;
    }

    private void encode(byte[] nv21, int w, int h) {
        RawListener raw = rawListener;
        if (raw != null) raw.onFrame(nv21, w, h);
        boolean asIs, turn;
        synchronized (frameLock) {
            asIs = viewers > webViewers;
            turn = webViewers > 0;
        }
        if (!asIs && !turn) return; // only face detection is watching: no JPEG needed
        long now = System.currentTimeMillis();
        if (now - lastEncode < FRAME_MS) return;
        lastEncode = now;
        byte[] plain = asIs ? jpeg(nv21, w, h) : null;
        byte[] up = null;
        if (turn) {
            if (turned == null || turned.length != nv21.length) turned = new byte[nv21.length];
            rotateClockwise(nv21, turned, w, h);
            up = jpeg(turned, h, w);
        }
        synchronized (frameLock) {
            if (plain != null) {
                frame = plain;
                frameNo++;
            }
            if (up != null) {
                upright = up;
                uprightNo++;
            }
            frameLock.notifyAll();
        }
    }

    private static byte[] jpeg(byte[] nv21, int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), QUALITY, out);
        return out.toByteArray();
    }

    /**
     * R2's camera is mounted on its side: turns an NV21 frame a quarter turn clockwise
     * (w x h in, h x w out), the grey plane pixel by pixel and the colour plane in pairs.
     */
    private static void rotateClockwise(byte[] in, byte[] out, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w, col = h - 1 - y;
            for (int x = 0; x < w; x++) out[x * h + col] = in[row + x];
        }
        int base = w * h, cw = w / 2, ch = h / 2;
        for (int y = 0; y < ch; y++) {
            int row = base + y * w, col = ch - 1 - y;
            for (int x = 0; x < cw; x++) {
                int o = base + (x * ch + col) * 2, i = row + x * 2;
                out[o] = in[i];
                out[o + 1] = in[i + 1];
            }
        }
    }

    // ---- HTTP (localhost only) -------------------------------------------------

    private void acceptLoop() {
        while (true) {
            try {
                ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
                server.setReuseAddress(true);
                while (true) {
                    final Socket s = server.accept();
                    Thread c = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(s);
                        }
                    }, "camera-conn");
                    c.setDaemon(true);
                    c.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "camera server failed, retrying", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void serve(Socket s) {
        boolean watching = false;
        try {
            s.setSoTimeout(10_000);
            String path = requestPath(s.getInputStream());
            OutputStream out = s.getOutputStream();
            boolean still = path.startsWith("/camera.jpg");
            if (!still && !path.startsWith("/camera.mjpg")) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(UTF8));
                return;
            }
            synchronized (frameLock) {
                viewers++;
                webViewers++;
                watching = true;
            }
            open();
            if (still) {
                byte[] f = nextFrame(-1, 5000, true);
                if (f == null) {
                    out.write(("HTTP/1.0 503 Unavailable\r\nContent-Type: text/plain\r\n\r\n" + status() + "\n").getBytes(UTF8));
                    return;
                }
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length
                        + "\r\nCache-Control: no-cache\r\n\r\n").getBytes(UTF8));
                out.write(f);
                return;
            }
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY
                    + "\r\nCache-Control: no-cache\r\nPragma: no-cache\r\n\r\n").getBytes(UTF8));
            long seen = -1;
            while (true) {
                byte[] f;
                synchronized (frameLock) {
                    f = nextFrameLocked(seen, 5000, true);
                    seen = uprightNo;
                }
                if (f == null) break; // camera stopped delivering
                out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n").getBytes(UTF8));
                out.write(f);
                out.write("\r\n".getBytes(UTF8));
                out.flush();
            }
        } catch (Exception ignored) {
            // viewer went away
        } finally {
            if (watching) {
                synchronized (frameLock) {
                    viewers--;
                    webViewers--;
                    if (viewers == 0) handler.postDelayed(closeIfIdle, CLOSE_AFTER_MS);
                }
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] nextFrame(long after, long timeoutMs, boolean turned) throws InterruptedException {
        synchronized (frameLock) {
            return nextFrameLocked(after, timeoutMs, turned);
        }
    }

    /**
     * Waits for a frame newer than frame number "after" (-1: any frame taken after this call), upright
     * or as the camera delivers it.
     */
    private byte[] nextFrameLocked(long after, long timeoutMs, boolean turned) throws InterruptedException {
        long want = after < 0 ? (turned ? uprightNo : frameNo) : after;
        long end = System.currentTimeMillis() + timeoutMs;
        while ((turned ? uprightNo : frameNo) <= want) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            frameLock.wait(left);
        }
        return turned ? upright : frame;
    }

    /** Reads the request head and returns the path of its request line. */
    private static String requestPath(InputStream in) throws Exception {
        String[] parts = readLine(in).split(" ");
        while (!readLine(in).isEmpty()) {
            // skip headers
        }
        return parts.length > 1 ? parts[1] : "";
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new Exception("header line too long");
        }
        return new String(b.toByteArray(), UTF8);
    }
}