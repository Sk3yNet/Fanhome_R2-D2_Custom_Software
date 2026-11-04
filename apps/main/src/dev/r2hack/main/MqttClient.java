package dev.r2hack.main;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;

/**
 * A small MQTT 3.1.1 client (no dependencies): connect with a last will and optional user name and
 * password, publish and subscribe at QoS 0, and keep the connection alive. Plain TCP only.
 * One thread runs readLoop(); any thread may publish. No Android classes, so it runs on a plain JVM too.
 */
final class MqttClient {
    interface Callback {
        void onMessage(String topic, byte[] payload);
    }

    static final class Will {
        final String topic;
        final byte[] payload;
        final boolean retain;

        Will(String topic, String payload, boolean retain) {
            this.topic = topic;
            this.payload = payload.getBytes(UTF8);
            this.retain = retain;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_INCOMING = 64 * 1024;   // we only expect short commands
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final Socket socket = new Socket();
    private final int keepAliveS;
    private OutputStream out;
    private DataInputStream in;
    private volatile long lastSent;
    private volatile boolean closed;
    private int nextPacketId = 1;

    MqttClient(int keepAliveS) {
        this.keepAliveS = keepAliveS;
    }

    /** Connects and waits for the broker's CONNACK. Throws with the broker's reason if refused. */
    void connect(InetAddress addr, int port, String clientId, String user, String password, Will will)
            throws IOException {
        socket.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        // Reads time out after one and a half keep-alive periods: by then a PINGRESP should have come.
        socket.setSoTimeout(keepAliveS * 1500);
        out = socket.getOutputStream();
        in = new DataInputStream(socket.getInputStream());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT");
        body.write(4); // protocol level 3.1.1
        int flags = 0x02; // clean session
        if (will != null) flags |= 0x04 | (will.retain ? 0x20 : 0);
        if (user != null && !user.isEmpty()) {
            flags |= 0x80;
            if (password != null && !password.isEmpty()) flags |= 0x40;
        }
        body.write(flags);
        body.write(keepAliveS >> 8);
        body.write(keepAliveS & 0xff);
        writeString(body, clientId);
        if (will != null) {
            writeString(body, will.topic);
            writeBytes(body, will.payload);
        }
        if ((flags & 0x80) != 0) writeString(body, user);
        if ((flags & 0x40) != 0) writeString(body, password);
        send(0x10, body.toByteArray());

        int type;
        byte[] ack;
        try {
            type = in.readUnsignedByte();
            ack = readBody();
        } catch (SocketTimeoutException e) {
            throw new IOException("no answer from the broker");
        }
        if ((type & 0xf0) != 0x20 || ack.length < 2) throw new IOException("not an MQTT broker");
        if (ack[1] != 0) throw new IOException(connackReason(ack[1]));
    }

    static String connackReason(int code) {
        switch (code) {
            case 1: return "broker refused: unsupported protocol version";
            case 2: return "broker refused the client ID";
            case 3: return "broker unavailable";
            case 4: return "broker refused: wrong user name or password";
            case 5: return "broker refused: not authorized";
            default: return "broker refused the connection (code " + code + ")";
        }
    }

    /** Whether the message being handed to Callback.onMessage is a retained one the broker replayed. */
    volatile boolean lastRetained;

    void publish(String topic, String payload, boolean retain) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, topic);
        byte[] p = payload.getBytes(UTF8);
        body.write(p, 0, p.length);
        send(0x30 | (retain ? 1 : 0), body.toByteArray());
    }

    /** Subscribes at QoS 0. The SUBACK is read (and ignored) by readLoop. */
    void subscribe(String... topics) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int id;
        synchronized (this) {
            id = nextPacketId;
            nextPacketId = nextPacketId % 0xffff + 1;
        }
        body.write(id >> 8);
        body.write(id & 0xff);
        for (String t : topics) {
            writeString(body, t);
            body.write(0);
        }
        send(0x82, body.toByteArray());
    }

    /** Sends PINGREQ if nothing was sent for half the keep-alive period. Call about once a second. */
    void keepAlive() throws IOException {
        if (System.currentTimeMillis() - lastSent > keepAliveS * 500L) send(0xc0, new byte[0]);
    }

    /** Reads packets until the connection ends, handing PUBLISH messages to cb. Always throws at the end. */
    void readLoop(Callback cb) throws IOException {
        try {
            while (true) {
                int type = in.readUnsignedByte();
                byte[] b = readBody();
                switch (type & 0xf0) {
                    case 0x30: { // PUBLISH
                        int qos = (type >> 1) & 3;
                        if (b.length < 2) throw new IOException("bad PUBLISH");
                        int tlen = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
                        if (2 + tlen > b.length) throw new IOException("bad PUBLISH");
                        String topic = new String(b, 2, tlen, UTF8);
                        int pos = 2 + tlen;
                        if (qos > 0) {
                            if (pos + 2 > b.length) throw new IOException("bad PUBLISH");
                            // QoS 1: acknowledge. QoS 2 shouldn't arrive (we subscribe at 0); answer it anyway.
                            send(qos == 1 ? 0x40 : 0x50, new byte[] {b[pos], b[pos + 1]});
                            pos += 2;
                        }
                        byte[] payload = new byte[b.length - pos];
                        System.arraycopy(b, pos, payload, 0, payload.length);
                        lastRetained = (type & 1) != 0;
                        cb.onMessage(topic, payload);
                        break;
                    }
                    case 0x60: // PUBREL (QoS 2) -> PUBCOMP
                        if (b.length >= 2) send(0x70, new byte[] {b[0], b[1]});
                        break;
                    default: // SUBACK, PINGRESP
                        break;
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("broker stopped answering");
        } catch (EOFException e) {
            throw new IOException(closed ? "disconnected" : "broker closed the connection");
        }
    }

    /** Sends DISCONNECT (so the broker doesn't publish the last will) and closes. */
    void disconnect() {
        closed = true;
        try {
            send(0xe0, new byte[0]);
        } catch (Exception ignored) {
        }
        close();
    }

    /** Closes without DISCONNECT: the broker publishes the last will. */
    void close() {
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    InetAddress remoteAddress() {
        return socket.getInetAddress();
    }

    private synchronized void send(int header, byte[] body) throws IOException {
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream pkt = new ByteArrayOutputStream(body.length + 5);
        pkt.write(header);
        int len = body.length;
        do {
            int digit = len % 128;
            len /= 128;
            pkt.write(len > 0 ? digit | 0x80 : digit);
        } while (len > 0);
        pkt.write(body, 0, body.length);
        out.write(pkt.toByteArray());
        out.flush();
        lastSent = System.currentTimeMillis();
    }

    private byte[] readBody() throws IOException {
        int len = 0, mult = 1, digit;
        int count = 0;
        do {
            digit = in.readUnsignedByte();
            len += (digit & 0x7f) * mult;
            mult *= 128;
            if (++count > 4) throw new IOException("bad packet length");
        } while ((digit & 0x80) != 0);
        if (len > MAX_INCOMING) throw new IOException("packet too large (" + len + " bytes)");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private static void writeString(ByteArrayOutputStream b, String s) {
        writeBytes(b, s.getBytes(UTF8));
    }

    private static void writeBytes(ByteArrayOutputStream b, byte[] d) {
        b.write(d.length >> 8);
        b.write(d.length & 0xff);
        b.write(d, 0, d.length);
    }
}
