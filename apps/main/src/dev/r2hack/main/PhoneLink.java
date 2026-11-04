package dev.r2hack.main;

import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compatibility with the official phone app, speaking the factory software's local protocol:
 * discovery broadcasts (UDP 8090), JSON commands over a WebSocket (8887), and camera video as JPEG
 * frames over a second WebSocket (12121).
 *
 * The original protocol has no cryptography. A phone is accepted only like the factory software does
 * it: while R2 runs its own hotspot, in pairing mode, or if it has paired before. On top of that, this
 * version never accepts software updates or controller resets from the phone, and only sends video to a
 * phone that holds an accepted control connection. It can be switched off (Brain: phone-app-off).
 */
final class PhoneLink implements Robot.Listener {
    interface Host {
        Robot robot();

        Sounds sounds();

        /** An app action by the phone app's number (the factory `mode` command). */
        void appMode(int mode);

        void drive(int power, int angle);

        void head(int angle);

        /** Passes a command straight to the controller. */
        void controller(JSONObject cmd);

        void projector(int mode);

        void powerOff();

        void toAgent(JSONObject msg);

        void wake();

        void pairingDone();

        void userControl(boolean anyone);
    }

    static final int CONTROL_PORT = 8887, VIDEO_PORT = 12121, DISCOVERY_PORT = 8090;
    private static final String TAG = "R2Phone";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long ACCESS_TIMEOUT_MS = 10_000, PING_MS = 5_000, LOST_MS = 15_000, CONTROL_TIMEOUT_MS = 12_000;

    private final Host host;
    private final CameraStream camera;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final Map<Integer, Client> pendingWifi = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile WebSocketConn videoViewer;

    private final class Client {
        final WebSocketConn ws;
        volatile String uuid;
        volatile boolean valid;
        volatile long controllingUntil;

        Client(WebSocketConn ws) {
            this.ws = ws;
        }

        String host() {
            return ws.socket.getInetAddress().getHostAddress();
        }
    }

    PhoneLink(Host host, CameraStream camera) {
        this.host = host;
        this.camera = camera;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            for (Client c : clients) c.ws.close();
            WebSocketConn v = videoViewer;
            if (v != null) v.close();
        }
    }

    boolean enabled() {
        return enabled;
    }

    String status() {
        int n = 0;
        for (Client c : clients) if (c.valid) n++;
        return "phone app: " + (enabled ? n + " connected" + (videoViewer != null ? ", watching video" : "") : "off");
    }

    void start() {
        thread("phone-discovery", new Runnable() {
            @Override
            public void run() {
                discoveryLoop();
            }
        });
        thread("phone-control", new Runnable() {
            @Override
            public void run() {
                serverLoop(CONTROL_PORT, false);
            }
        });
        thread("phone-video", new Runnable() {
            @Override
            public void run() {
                serverLoop(VIDEO_PORT, true);
            }
        });
        thread("phone-keepalive", new Runnable() {
            @Override
            public void run() {
                keepaliveLoop();
            }
        });
    }

    // ---- discovery -----------------------------------------------------------------

    private void discoveryLoop() {
        while (true) {
            try {
                Thread.sleep(3000);
                if (!enabled) continue;
                Robot r = host.robot();
                String ip = Ssdp.localIp();
                InetAddress bcast = broadcastAddress();
                if (ip == null || bcast == null) continue;
                byte[] msg = new JSONObject().put("cmd", "updBroadcast").put("ip", ip).put("uuid", r.uuid())
                        .put("name", r.name()).put("ap_mode", r.apMode).toString().getBytes(UTF8);
                DatagramSocket s = new DatagramSocket();
                try {
                    s.setBroadcast(true);
                    s.send(new DatagramPacket(msg, msg.length, bcast, DISCOVERY_PORT));
                } finally {
                    s.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "discovery broadcast failed: " + e);
            }
        }
    }

    private static InetAddress broadcastAddress() throws Exception {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                if (a.getAddress() instanceof Inet4Address && a.getAddress().isSiteLocalAddress() && a.getBroadcast() != null) {
                    return a.getBroadcast();
                }
            }
        }
        return null;
    }

    // ---- servers ---------------------------------------------------------------------

    private void serverLoop(int port, final boolean video) {
        while (true) {
            try {
                ServerSocket server = new ServerSocket(port);
                server.setReuseAddress(true);
                while (true) {
                    final Socket s = server.accept();
                    thread(video ? "phone-video-conn" : "phone-conn", new Runnable() {
                        @Override
                        public void run() {
                            if (video) serveVideo(s);
                            else serveControl(s);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "server on " + port + " failed, retrying", e);
                sleep(5000);
            }
        }
    }

    private static boolean local(InetAddress a) {
        return a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress();
    }

    private void serveControl(Socket s) {
        Client me = null;
        try {
            if (!enabled || !local(s.getInetAddress())) {
                s.close();
                return;
            }
            WebSocketConn ws = new WebSocketConn(s);
            if (!ws.handshake()) return;
            me = new Client(ws);
            for (Client c : clients) {
                if (c.host().equals(me.host())) c.ws.close(); // same phone reconnecting: drop the old one
            }
            clients.add(me);
            final Client self = me;
            thread("phone-access-timer", new Runnable() {
                @Override
                public void run() {
                    sleep(ACCESS_TIMEOUT_MS);
                    if (!self.valid) self.ws.close();
                }
            });
            StringBuilder buffered = new StringBuilder();
            WebSocketConn.Message m;
            while ((m = ws.read()) != null) {
                if (m.text == null) continue;
                buffered.append(m.text);
                int nl;
                while ((nl = buffered.indexOf("\n")) >= 0) {
                    String line = buffered.substring(0, nl).trim();
                    buffered.delete(0, nl + 1);
                    if (!line.isEmpty()) handle(me, line);
                }
                if (buffered.length() > 0 && buffered.charAt(0) == '{') {
                    // Some clients send one JSON object per message without a newline.
                    try {
                        new JSONObject(buffered.toString());
                        handle(me, buffered.toString());
                        buffered.setLength(0);
                    } catch (Exception incomplete) {
                        // wait for the rest
                    }
                }
            }
        } catch (Exception e) {
            // phone went away
        } finally {
            if (me != null) {
                me.ws.close();
                clients.remove(me);
                updateControl();
            }
        }
    }

    private void serveVideo(Socket s) {
        boolean watching = false;
        try {
            InetAddress from = s.getInetAddress();
            if (!enabled || !local(from)) {
                s.close();
                return;
            }
            WebSocketConn ws = new WebSocketConn(s);
            if (!ws.handshake()) return;
            if (!hasValidClient(from.getHostAddress())) {
                ws.sendText(reply(new JSONObject(), "streaming", 0, 401));
                ws.close();
                return;
            }
            synchronized (this) {
                if (videoViewer != null) {
                    ws.sendText(new JSONObject().put("cmd", "streaming").put("resultCode", 421).toString() + "\n");
                    ws.close();
                    return;
                }
                videoViewer = ws;
            }
            ws.sendText("enter video socket");
            camera.addViewer();
            watching = true;
            host.wake();
            long[] seen = {-1};
            while (!ws.isClosed() && enabled && hasValidClient(from.getHostAddress())) {
                byte[] f = camera.next(seen, 5000);
                if (f != null) ws.sendBinary(f);
            }
        } catch (Exception e) {
            // viewer went away
        } finally {
            if (watching) camera.removeViewer();
            synchronized (this) {
                videoViewer = null;
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasValidClient(String hostAddress) {
        for (Client c : clients) if (c.valid && c.host().equals(hostAddress)) return true;
        return false;
    }

    private void keepaliveLoop() {
        while (true) {
            sleep(PING_MS);
            long now = System.currentTimeMillis();
            for (Client c : clients) {
                if (now - c.ws.lastHeard() > LOST_MS) {
                    c.ws.close();
                    continue;
                }
                c.ws.ping();
            }
            updateControl();
        }
    }

    // ---- commands ------------------------------------------------------------------------

    private void handle(Client c, String line) {
        JSONObject j;
        try {
            j = new JSONObject(line);
        } catch (Exception e) {
            return;
        }
        String cmd = j.optString("cmd");
        int seq = j.optInt("seq");
        Robot r = host.robot();
        try {
            if ("grantAccess".equals(cmd)) {
                grantAccess(c, j, seq);
                return;
            }
            if (!c.valid) return;
            switch (cmd) {
                case "getWifiList":
                    pendingWifi.put(seq, c);
                    host.toAgent(new JSONObject().put("agent", "wifi-scan").put("seq", seq));
                    return;
                case "connectWifi":
                    pendingWifi.put(seq, c);
                    host.toAgent(new JSONObject().put("agent", "wifi-join").put("seq", seq)
                            .put("ssid", j.optString("ssid")).put("password", j.optString("wifi_pw")));
                    return;
                case "face_detection":
                    r.setFlag("face_detection", j.optBoolean("enable"));
                    c.ws.sendText(reply(new JSONObject(), cmd, seq, 0));
                    return;
                case "voice_recognition":
                    r.setFlag("voice_recognition", j.optBoolean("enable"));
                    c.ws.sendText(reply(new JSONObject(), cmd, seq, 0));
                    return;
                case "mute":
                    host.sounds().setMuted(j.optBoolean("enable"));
                    r.changed();
                    c.ws.sendText(reply(new JSONObject(), cmd, seq, 0));
                    return;
                case "power":
                    c.ws.sendText(reply(new JSONObject(), cmd, seq, 0));
                    host.powerOff();
                    return;
                case "user_control":
                    c.controllingUntil = j.optBoolean("enable") ? System.currentTimeMillis() + CONTROL_TIMEOUT_MS : 0;
                    updateControl();
                    return;
                case "change_name":
                    c.ws.sendText(reply(new JSONObject(), cmd, seq, r.setName(j.optString("new_name")) ? 0 : 422));
                    return;
                case "paired_list":
                    c.ws.sendText(reply(new JSONObject().put("clients", r.clients()), cmd, seq, 0));
                    return;
                case "unpair": {
                    String uuid = j.has("uuid") && !j.isNull("uuid") ? j.optString("uuid") : null;
                    boolean ok = r.removeClient(uuid);
                    c.ws.sendText(reply(new JSONObject().put("clients", r.clients()), cmd, seq, ok ? 0 : 423));
                    for (Client other : clients) {
                        if (uuid == null || uuid.equals(other.uuid)) other.ws.close();
                    }
                    return;
                }
                default:
                    break;
            }
            if (r.mode == Robot.PAIR) return; // the factory software ignores control while pairing
            host.wake();
            switch (cmd) {
                case "move":
                    host.drive(j.optInt("power"), j.optInt("angle"));
                    break;
                case "move-head":
                    host.head(j.optInt("angle"));
                    break;
                case "head-dir":
                    host.controller(new JSONObject().put("cmd", "head-dir").put("dir", j.optInt("dir")));
                    break;
                case "projector":
                    host.projector(j.optInt("mode"));
                    break;
                case "mode":
                    host.appMode(j.optInt("mode"));
                    break;
                case "play_sound":
                    host.sounds().play(j.optInt("sound_id"), j.optInt("interrupt") == 1);
                    break;
                case "lcd":
                case "led": {
                    JSONObject out = new JSONObject().put("cmd", cmd);
                    for (String k : "lcd".equals(cmd) ? new String[] {"s", "l"} : new String[] {"r", "b", "y", "g"}) {
                        if (j.has(k) && j.optInt(k, -1) != -1) out.put(k, j.optInt(k));
                    }
                    host.controller(out);
                    break;
                }
                case "reset-wdt":
                case "d-head-power":
                case "d-leg-power": {
                    JSONObject out = new JSONObject().put("cmd", cmd);
                    if (j.has("power")) out.put("power", j.optInt("power"));
                    host.controller(out);
                    break;
                }
                case "self_update":
                case "self_update_unsafe":
                case "reset_mcu":
                    Log.w(TAG, "refused " + cmd + " from the phone app");
                    break;
                default:
                    Log.i(TAG, "unknown phone command " + cmd);
            }
        } catch (Exception e) {
            Log.w(TAG, "phone command " + cmd + " failed", e);
        }
    }

    private void grantAccess(Client c, JSONObject j, int seq) throws Exception {
        String uuid = j.optString("uuid");
        Robot r = host.robot();
        if (uuid.isEmpty()) {
            c.ws.sendText(reply(new JSONObject(), "grantAccess", seq, 301));
            c.ws.close();
            return;
        }
        boolean known = r.isClient(uuid);
        if (r.apMode || known || r.mode == Robot.PAIR) {
            if (!known) r.addClient(uuid, j.optString("device_name"));
            c.uuid = uuid;
            c.valid = true;
            Log.i(TAG, "phone accepted: " + j.optString("device_name"));
            c.ws.sendText(reply(new JSONObject().put("robot", r.toPhoneJson()), "grantAccess", seq, 0));
            if (r.mode == Robot.PAIR) host.pairingDone();
        } else {
            Log.i(TAG, "phone refused (not paired; press the pairing button first)");
            c.ws.sendText(reply(new JSONObject(), "grantAccess", seq, 401));
            c.ws.close();
        }
    }

    /** Replies from the agent to Wi-Fi requests made for a phone. */
    void onAgentMessage(JSONObject msg) {
        String kind = msg.optString("agent");
        Client c = pendingWifi.remove(msg.optInt("seq"));
        if (c == null) return;
        try {
            if ("wifi-list".equals(kind)) {
                c.ws.sendText(reply(new JSONObject().put("wifi_list", msg.optJSONArray("list") == null ? new JSONArray() : msg.optJSONArray("list"))
                        .put("currentSSID", msg.optString("current")), "getWifiList", msg.optInt("seq"), 0));
            } else if ("wifi-join-result".equals(kind)) {
                c.ws.sendText(reply(new JSONObject(), "connectWifi", msg.optInt("seq"), msg.optBoolean("ok") ? 0 : 411));
            }
        } catch (Exception ignored) {
        }
    }

    /** Robot info pushed to phones on every change, as the factory software does. */
    @Override
    public void onRobotChanged() {
        String msg;
        try {
            msg = new JSONObject().put("cmd", "gin").put("robot", host.robot().toPhoneJson()).toString() + "\n";
        } catch (Exception e) {
            return;
        }
        for (Client c : clients) if (c.valid) c.ws.sendText(msg);
    }

    private void updateControl() {
        long now = System.currentTimeMillis();
        boolean anyone = false;
        for (Client c : clients) if (c.valid && c.controllingUntil > now) anyone = true;
        host.userControl(anyone);
    }

    private static String reply(JSONObject o, String cmd, int seq, int code) throws Exception {
        return o.put("resultCode", code).put("cmd", cmd).put("seq", seq).toString() + "\n";
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
