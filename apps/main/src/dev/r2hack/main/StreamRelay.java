package dev.r2hack.main;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Hands a network stream to the board's media player as a plain HTTP/1.0 stream on 127.0.0.1.
 * The player can't read chunked streams with no length (how Music Assistant sends audio), so this
 * fetches the stream itself (HttpURLConnection undoes the chunking) and passes the bytes on unchanged.
 * Only the one stream currently set is served, and only to this device.
 */
final class StreamRelay {
    static final int PORT = 7712;
    private static final String TAG = "R2Relay";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int HEAD = 256 << 10; // the stream's start, kept for the player's re-probes
    private static final int RING = 4 << 20;   // the newest 4 MB (about 100 s of MP3), for readers a little behind
    private static final long LIVE_LENGTH = 2_000_000_000L; // stand-in length for a live stream (about 14 h of MP3)

    private volatile String upstream;
    private volatile int generation;
    private Upstream current; // guarded by this

    /** Serves this stream from now on (replacing any earlier one); returns the address for the player. */
    synchronized String relay(String url) {
        upstream = url;
        if (current != null) current.close();
        current = null;
        return "http://127.0.0.1:" + PORT + "/stream/" + (++generation);
    }

    /**
     * One connection to the stream's source, shared by the player's requests. The player probes the stream
     * and then opens it again, but a Music Assistant stream address only works once, so the start of the
     * stream is kept and a new request is served from it before reading on.
     */
    private static final class Upstream {
        final HttpURLConnection conn;
        final InputStream in;
        final String type;
        final long length;
        final byte[] kept = new byte[HEAD];     // the stream's first bytes, for the player's probes
        int keptLen;
        final byte[] ring = new byte[RING];     // the most recent bytes, so two readers a little apart both work
        long readTotal;       // bytes read from the source so far
        boolean ended;

        Upstream(String url) throws Exception {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                throw new Exception("stream answered " + code);
            }
            in = conn.getInputStream();
            type = conn.getContentType() == null ? "audio/mpeg" : conn.getContentType();
            length = conn.getContentLength();
        }

        /** Copies bytes from position pos into buf; returns how many, or -1 at the end (or if pos was dropped). */
        synchronized int read(long pos, byte[] buf) throws Exception {
            if (pos < keptLen) {
                int n = (int) Math.min(buf.length, keptLen - pos);
                System.arraycopy(kept, (int) pos, buf, 0, n);
                return n;
            }
            if (pos < readTotal) {                       // behind the newest data: serve it from the ring
                if (readTotal - pos > RING) return -1;   // too far behind, no longer kept
                int n = (int) Math.min(buf.length, readTotal - pos);
                int at = (int) (pos % RING), first = Math.min(n, RING - at);
                System.arraycopy(ring, at, buf, 0, first);
                if (n > first) System.arraycopy(ring, 0, buf, first, n - first);
                return n;
            }
            if (pos > readTotal || ended) return -1;     // asked for data that hasn't arrived, or the stream is over
            int n;
            try {
                n = in.read(buf);
            } catch (Exception e) {
                ended = true;
                Log.w(TAG, "source failed after " + readTotal + " bytes: " + e);
                return -1;
            }
            if (n <= 0) {
                ended = true;
                Log.i(TAG, "source ended after " + readTotal + " bytes");
                return -1;
            }
            if (keptLen < HEAD) {
                int k = Math.min(n, HEAD - keptLen);
                System.arraycopy(buf, 0, kept, keptLen, k);
                keptLen += k;
            }
            int at = (int) (readTotal % RING), first = Math.min(n, RING - at);
            System.arraycopy(buf, 0, ring, at, first);
            if (n > first) System.arraycopy(buf, first, ring, 0, n - first);
            readTotal += n;
            return n;
        }

        /** True if the stream is over and nothing at pos can be served. */
        synchronized boolean isOver(long pos) {
            return ended && pos >= readTotal && pos >= keptLen;
        }

        void close() {
            conn.disconnect();
        }
    }

    private synchronized Upstream source(String url) throws Exception {
        if (current == null) current = new Upstream(url);
        return current;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "stream-relay");
        t.setDaemon(true);
        t.start();
    }

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
                    }, "stream-relay-conn");
                    c.setDaemon(true);
                    c.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "relay server failed, retrying", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(15_000);
            long[] range = {-1};
            String path = requestPath(s.getInputStream(), range);
            OutputStream out = s.getOutputStream();
            String url = upstream;
            if (url == null || !path.equals("/stream/" + generation)) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(UTF8));
                return;
            }
            Upstream src;
            try {
                src = source(url);
            } catch (Exception e) {
                Log.w(TAG, String.valueOf(e.getMessage()));
                out.write("HTTP/1.0 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(UTF8));
                return;
            }
            long pos = Math.max(0, range[0]);
            if (src.isOver(pos)) {
                // Nothing more will come: say so, or the player asks again and again.
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(UTF8));
                return;
            }
            Log.i(TAG, "player request" + (range[0] >= 0 ? " from byte " + range[0] : "") + ": " + src.type
                    + (src.length > 0 ? ", " + src.length + " bytes" : ", live stream"));
            if (pos > 0) {
                // The player skips the MP3 tag and asks for the rest. A live stream has no length, so give it a
                // very large one: the player only needs the range answered.
                long total = src.length > 0 ? src.length : LIVE_LENGTH;
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: " + src.type + "\r\nContent-Range: bytes " + pos
                        + "-" + (total - 1) + "/" + total + "\r\nContent-Length: " + (total - pos)
                        + "\r\nConnection: close\r\n\r\n").getBytes(UTF8));
            } else {
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: " + src.type + "\r\nAccept-Ranges: bytes\r\n"
                        + (src.length > 0 ? "Content-Length: " + src.length + "\r\n" : "") + "\r\n").getBytes(UTF8));
            }
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(pos, buf)) > 0) {
                out.write(buf, 0, n);
                pos += n;
            }
        } catch (Exception ignored) {
            // the player stopped reading, or the stream ended
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Reads the request head; returns the path, and the start of a "Range: bytes=N-" request in range[0]. */
    private static String requestPath(InputStream in, long[] range) throws Exception {
        String[] parts = readLine(in).split(" ");
        String h;
        while (!(h = readLine(in)).isEmpty()) {
            String l = h.toLowerCase(Locale.US);
            if (l.startsWith("range:") && l.contains("bytes=")) {
                String v = l.substring(l.indexOf("bytes=") + 6).trim();
                int dash = v.indexOf('-');
                try {
                    range[0] = Long.parseLong(dash < 0 ? v : v.substring(0, dash).trim());
                } catch (NumberFormatException ignored) {
                }
            }
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
