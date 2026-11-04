package dev.r2hack.main;

import android.util.Base64;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One server-side WebSocket connection (RFC 6455): handshake, text and binary messages, ping, close. */
final class WebSocketConn {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 1 << 20;

    final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile long lastHeard = System.currentTimeMillis();
    private volatile boolean closed;

    /** A received message: text != null for text frames, otherwise binary data. */
    static final class Message {
        final String text;
        final byte[] data;

        Message(String text, byte[] data) {
            this.text = text;
            this.data = data;
        }
    }

    WebSocketConn(Socket socket) throws Exception {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Reads the HTTP upgrade request and answers it. Returns false (and closes) if it isn't a WebSocket. */
    boolean handshake() throws Exception {
        String request = readLine();
        Map<String, String> h = new HashMap<>();
        String line;
        while (!(line = readLine()).isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        String key = h.get("sec-websocket-key");
        String upgrade = h.get("upgrade");
        if (!request.startsWith("GET ") || key == null || upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            write(("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(UTF8));
            close();
            return false;
        }
        byte[] sha = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(UTF8));
        String accept = Base64.encodeToString(sha, Base64.NO_WRAP);
        write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(UTF8));
        return true;
    }

    long lastHeard() {
        return lastHeard;
    }

    boolean isClosed() {
        return closed;
    }

    /** Blocks for the next text or binary message; answers pings itself. Returns null when closed. */
    Message read() throws Exception {
        ByteArrayOutputStream partial = null;
        int partialOp = 0;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new EOFException();
            lastHeard = System.currentTimeMillis();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) len = (readByte() << 8) | readByte();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > MAX_MESSAGE) throw new Exception("frame too large");
            byte[] mask = new byte[4];
            if (masked) readFully(mask);
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            if (op == 8) { // close
                sendFrame(8, new byte[0]);
                close();
                return null;
            } else if (op == 9) { // ping
                sendFrame(10, payload);
            } else if (op == 10) { // pong
                // lastHeard already updated
            } else if (op == 0 || op == 1 || op == 2) {
                if (op != 0) {
                    partial = new ByteArrayOutputStream();
                    partialOp = op;
                }
                if (partial == null) throw new Exception("continuation without start");
                partial.write(payload);
                if (partial.size() > MAX_MESSAGE) throw new Exception("message too large");
                if (fin) {
                    byte[] all = partial.toByteArray();
                    return partialOp == 1 ? new Message(new String(all, UTF8), null) : new Message(null, all);
                }
            }
        }
    }

    void sendText(String text) {
        sendFrame(1, text.getBytes(UTF8));
    }

    void sendBinary(byte[] data) {
        sendFrame(2, data);
    }

    void ping() {
        sendFrame(9, new byte[0]);
    }

    synchronized void sendFrame(int op, byte[] payload) {
        if (closed) return;
        try {
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 10);
            f.write(0x80 | op);
            if (payload.length < 126) {
                f.write(payload.length);
            } else if (payload.length < 65536) {
                f.write(126);
                f.write(payload.length >> 8);
                f.write(payload.length);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) (((long) payload.length) >> (8 * i)));
            }
            f.write(payload);
            out.write(f.toByteArray());
            out.flush();
        } catch (Exception e) {
            close();
        }
    }

    void close() {
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private synchronized void write(byte[] b) throws Exception {
        out.write(b);
        out.flush();
    }

    private int readByte() throws Exception {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private void readFully(byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private String readLine() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new Exception("header line too long");
        }
        if (c == -1 && b.size() == 0) throw new EOFException();
        return new String(b.toByteArray(), UTF8);
    }
}
