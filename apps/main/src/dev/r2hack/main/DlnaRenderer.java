package dev.r2hack.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R2 as a DLNA/UPnP media renderer: Home Assistant discovers it (SSDP) and shows a media player,
 * so music, text-to-speech and alerts play through the robot's speaker. Implements AVTransport,
 * RenderingControl (volume, mute) and ConnectionManager, with GENA events so the player state in
 * Home Assistant stays current. Volume and mute are shared with the droid sounds.
 */
final class DlnaRenderer implements MediaPlayback.Listener {
    static final int PORT = 7710;
    private static final String TAG = "R2Dlna";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SUBSCRIPTION_S = 1800;

    private final String udn;
    private final String name;
    private final Sounds sounds;
    private final MediaPlayback media = new MediaPlayback(this);
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final Map<String, Subscription> subs = new ConcurrentHashMap<>();
    private final Ssdp ssdp;

    private static final class Subscription {
        final String service;
        final String callback;
        long expires;
        long seq;

        Subscription(String service, String callback) {
            this.service = service;
            this.callback = callback;
        }
    }

    DlnaRenderer(Context ctx, Sounds sounds) {
        SharedPreferences prefs = ctx.getSharedPreferences("dlna", Context.MODE_PRIVATE);
        String id = prefs.getString("udn", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("udn", id).apply();
        }
        this.udn = id;
        this.name = prefs.getString("name", "R2-D2");
        this.sounds = sounds;
        this.ssdp = new Ssdp(ctx, udn, PORT);
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "dlna-http");
        t.setDaemon(true);
        t.start();
        ssdp.start();
    }

    String status() {
        return "media: " + media.state() + (media.uri().isEmpty() ? "" : " " + media.uri());
    }

    boolean playing() {
        return MediaPlayback.PLAYING.equals(media.state());
    }

    /** Stops any stream, e.g. on the stop action. */
    void stopMedia() {
        media.stop();
    }

    // ---- HTTP ----------------------------------------------------------------

    private void acceptLoop() {
        while (true) {
            try {
                ServerSocket server = new ServerSocket(PORT);
                server.setReuseAddress(true);
                Log.i(TAG, "renderer listening on :" + PORT);
                while (true) {
                    final Socket s = server.accept();
                    Thread c = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(s);
                        }
                    }, "dlna-conn");
                    c.setDaemon(true);
                    c.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "renderer server failed, retrying", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void serve(Socket s) {
        int code = 200;
        String type = "text/xml; charset=\"utf-8\"", body = "";
        Map<String, String> extra = new HashMap<>();
        try {
            s.setSoTimeout(15_000);
            InetAddress from = s.getInetAddress();
            if (!(from.isSiteLocalAddress() || from.isLinkLocalAddress() || from.isLoopbackAddress())) {
                s.close();
                return;
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            String[] req = readLine(in).split(" ");
            if (req.length < 2) throw new Exception("bad request line");
            String method = req[0].toUpperCase(Locale.US), path = req[1];
            Map<String, String> h = new HashMap<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            String reqBody = readBody(in, h.get("content-length"));

            if ("GET".equals(method) || "HEAD".equals(method)) {
                switch (path) {
                    case "/dlna/description.xml": body = DlnaXml.device(udn, name); break;
                    case "/dlna/avt.xml": body = DlnaXml.avTransport(); break;
                    case "/dlna/rc.xml": body = DlnaXml.renderingControl(); break;
                    case "/dlna/cm.xml": body = DlnaXml.connectionManager(); break;
                    default: code = 404;
                }
            } else if ("POST".equals(method) && path.startsWith("/dlna/control/")) {
                String svc = path.substring("/dlna/control/".length());
                String soapAction = h.get("soapaction");
                String action = soapAction == null ? "" : soapAction.replace("\"", "");
                action = action.substring(action.indexOf('#') + 1);
                try {
                    body = control(svc, action, reqBody, from);
                } catch (UpnpError e) {
                    code = 500;
                    body = DlnaXml.soapFault(e.code, e.getMessage());
                }
            } else if ("SUBSCRIBE".equals(method) && path.startsWith("/dlna/event/")) {
                code = subscribe(path.substring("/dlna/event/".length()), h, extra);
            } else if ("UNSUBSCRIBE".equals(method)) {
                String sid = h.get("sid");
                code = sid != null && subs.remove(sid) != null ? 200 : 412;
            } else {
                code = 405;
            }
            if ("HEAD".equals(method)) body = "";
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
            code = 500;
            body = "";
        }
        try {
            byte[] b = body.getBytes(UTF8);
            StringBuilder head = new StringBuilder("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n");
            if (b.length > 0) head.append("Content-Type: ").append(type).append("\r\n");
            head.append("Content-Length: ").append(b.length).append("\r\nServer: Android/4.4 UPnP/1.0 R2Main\r\n");
            for (Map.Entry<String, String> e : extra.entrySet()) head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(UTF8));
            out.write(b);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- SOAP actions ----------------------------------------------------------

    private static final class UpnpError extends Exception {
        final int code;

        UpnpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private String control(String svc, String action, String body, InetAddress from) throws UpnpError {
        switch (svc) {
            case "avt": return DlnaXml.soapResponse(DlnaXml.AVT, action, avTransport(action, body, from));
            case "rc": return DlnaXml.soapResponse(DlnaXml.RC, action, renderingControl(action, body));
            case "cm": return DlnaXml.soapResponse(DlnaXml.CM, action, connectionManager(action));
            default: throw new UpnpError(401, "Invalid Action");
        }
    }

    private static final java.util.regex.Pattern MA_STREAM =
            java.util.regex.Pattern.compile("^(https?://[^/?#]+/(?:flow|single|multi)/[^?#]*)\\.(?:flac|wav|aiff|pcm)(\\?.*)?$");

    /**
     * Music Assistant sends FLAC as an endless chunked stream, which this board's player can't play, and it
     * ignores the formats we advertise. Its stream server picks the format from the file extension, so ask
     * for MP3 instead.
     */
    static String preferMp3(String uri) {
        java.util.regex.Matcher m = MA_STREAM.matcher(uri);
        if (!m.matches()) return uri;
        Log.i(TAG, "asking Music Assistant for MP3 instead");
        return m.group(1) + ".mp3" + (m.group(2) == null ? "" : m.group(2));
    }

    private String avTransport(String action, String body, InetAddress from) throws UpnpError {
        switch (action) {
            case "SetAVTransportURI": {
                String uri = soapArg(body, "CurrentURI");
                String why = MediaPlayback.refuse(uri, from);
                if (why != null) {
                    Log.w(TAG, "refused stream " + uri + ": " + why);
                    throw new UpnpError(716, "Resource not found: " + why);
                }
                String meta = soapArg(body, "CurrentURIMetaData");
                java.util.regex.Matcher type = java.util.regex.Pattern.compile("protocolInfo=\"[^:]*:[^:]*:([^:\"]+)").matcher(DlnaXml.unesc(meta));
                int q = uri.indexOf('?');
                Log.i(TAG, "stream " + (q < 0 ? uri : uri.substring(0, q)) + " (" + (type.find() ? type.group(1) : "type not given") + ")");
                uri = preferMp3(uri);
                media.setUri(uri, meta);
                return "";
            }
            case "Play": media.play(); return "";
            case "Pause": media.pause(); return "";
            case "Stop": media.stop(); return "";
            case "Seek": {
                String unit = soapArg(body, "Unit");
                if (!"REL_TIME".equals(unit) && !"ABS_TIME".equals(unit)) throw new UpnpError(710, "Seek mode not supported");
                media.seekMs(parseTime(soapArg(body, "Target")));
                return "";
            }
            case "GetTransportInfo":
                return DlnaXml.arg("CurrentTransportState", media.state()) + DlnaXml.arg("CurrentTransportStatus", "OK")
                        + DlnaXml.arg("CurrentSpeed", "1");
            case "GetPositionInfo": {
                String t = time(media.positionMs()), d = time(media.durationMs());
                boolean has = !media.uri().isEmpty();
                return DlnaXml.arg("Track", has ? 1 : 0) + DlnaXml.arg("TrackDuration", d)
                        + DlnaXml.arg("TrackMetaData", media.metadata()) + DlnaXml.arg("TrackURI", media.uri())
                        + DlnaXml.arg("RelTime", t) + DlnaXml.arg("AbsTime", t)
                        + DlnaXml.arg("RelCount", 2147483647) + DlnaXml.arg("AbsCount", 2147483647);
            }
            case "GetMediaInfo":
                return DlnaXml.arg("NrTracks", media.uri().isEmpty() ? 0 : 1) + DlnaXml.arg("MediaDuration", time(media.durationMs()))
                        + DlnaXml.arg("CurrentURI", media.uri()) + DlnaXml.arg("CurrentURIMetaData", media.metadata())
                        + DlnaXml.arg("NextURI", "") + DlnaXml.arg("NextURIMetaData", "")
                        + DlnaXml.arg("PlayMedium", "NETWORK") + DlnaXml.arg("RecordMedium", "NOT_IMPLEMENTED")
                        + DlnaXml.arg("WriteStatus", "NOT_IMPLEMENTED");
            case "GetDeviceCapabilities":
                return DlnaXml.arg("PlayMedia", "NETWORK") + DlnaXml.arg("RecMedia", "NOT_IMPLEMENTED")
                        + DlnaXml.arg("RecQualityModes", "NOT_IMPLEMENTED");
            case "GetTransportSettings":
                return DlnaXml.arg("PlayMode", "NORMAL") + DlnaXml.arg("RecQualityMode", "NOT_IMPLEMENTED");
            case "GetCurrentTransportActions":
                return DlnaXml.arg("Actions", transportActions());
            default:
                throw new UpnpError(401, "Invalid Action");
        }
    }

    private String renderingControl(String action, String body) throws UpnpError {
        switch (action) {
            case "GetVolume": return DlnaXml.arg("CurrentVolume", sounds.volume());
            case "SetVolume":
                try {
                    sounds.setVolume(Integer.parseInt(soapArg(body, "DesiredVolume").trim()));
                } catch (NumberFormatException e) {
                    throw new UpnpError(402, "Invalid Args");
                }
                renderingChanged();
                return "";
            case "GetMute": return DlnaXml.arg("CurrentMute", sounds.muted() ? 1 : 0);
            case "SetMute": {
                String v = soapArg(body, "DesiredMute").trim();
                sounds.setMuted("1".equals(v) || "true".equalsIgnoreCase(v));
                renderingChanged();
                return "";
            }
            default: throw new UpnpError(401, "Invalid Action");
        }
    }

    private String connectionManager(String action) throws UpnpError {
        switch (action) {
            case "GetProtocolInfo": return DlnaXml.arg("Source", "") + DlnaXml.arg("Sink", DlnaXml.SINK_PROTOCOLS);
            case "GetCurrentConnectionIDs": return DlnaXml.arg("ConnectionIDs", "0");
            case "GetCurrentConnectionInfo":
                return DlnaXml.arg("RcsID", 0) + DlnaXml.arg("AVTransportID", 0) + DlnaXml.arg("ProtocolInfo", "")
                        + DlnaXml.arg("PeerConnectionManager", "") + DlnaXml.arg("PeerConnectionID", -1)
                        + DlnaXml.arg("Direction", "Input") + DlnaXml.arg("Status", "OK");
            default: throw new UpnpError(401, "Invalid Action");
        }
    }

    private String transportActions() {
        String st = media.state();
        if (MediaPlayback.PLAYING.equals(st)) return "Pause,Stop,Seek";
        if (MediaPlayback.PAUSED.equals(st)) return "Play,Stop,Seek";
        if (MediaPlayback.TRANSITIONING.equals(st)) return "Stop";
        return media.uri().isEmpty() ? "" : "Play";
    }

    // ---- GENA events -------------------------------------------------------------

    private int subscribe(String service, Map<String, String> h, Map<String, String> reply) {
        String sid = h.get("sid");
        long now = System.currentTimeMillis();
        if (sid != null) {                               // renewal
            Subscription sub = subs.get(sid);
            if (sub == null) return 412;
            sub.expires = now + SUBSCRIPTION_S * 1000L;
        } else {
            String cb = h.get("callback");
            if (cb == null || !"upnp:event".equals(h.get("nt"))) return 412;
            Matcher m = Pattern.compile("<([^>]+)>").matcher(cb);
            if (!m.find()) return 412;
            sid = "uuid:" + UUID.randomUUID();
            Subscription sub = new Subscription(service, m.group(1));
            sub.expires = now + SUBSCRIPTION_S * 1000L;
            subs.put(sid, sub);
            final String newSid = sid;
            events.execute(new Runnable() {          // the spec wants an initial event with the full state
                @Override
                public void run() {
                    Subscription s = subs.get(newSid);
                    if (s != null) sendEvent(newSid, s, eventBody(s.service));
                }
            });
        }
        reply.put("SID", sid);
        reply.put("TIMEOUT", "Second-" + SUBSCRIPTION_S);
        return 200;
    }

    @Override
    public void onStateChanged() {
        broadcast("avt");
    }

    /** Volume or mute changed elsewhere (web page, phone app): tells Home Assistant. */
    void volumeChanged() {
        renderingChanged();
    }

    private void renderingChanged() {
        broadcast("rc");
    }

    private void broadcast(final String service) {
        events.execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                String body = eventBody(service);
                Iterator<Map.Entry<String, Subscription>> it = subs.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Subscription> e = it.next();
                    if (e.getValue().expires < now) {
                        it.remove();
                    } else if (e.getValue().service.equals(service)) {
                        sendEvent(e.getKey(), e.getValue(), body);
                    }
                }
            }
        });
    }

    private String eventBody(String service) {
        switch (service) {
            case "avt": {
                String change = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                        + val("TransportState", media.state()) + val("TransportStatus", "OK")
                        + val("CurrentTransportActions", transportActions())
                        + val("AVTransportURI", media.uri()) + val("CurrentTrackURI", media.uri())
                        + val("AVTransportURIMetaData", media.metadata()) + val("CurrentTrackMetaData", media.metadata())
                        + val("CurrentMediaDuration", time(media.durationMs())) + val("CurrentTrackDuration", time(media.durationMs()))
                        + val("NumberOfTracks", media.uri().isEmpty() ? "0" : "1")
                        + "</InstanceID></Event>";
                return DlnaXml.propertySet("LastChange", change);
            }
            case "rc": {
                String change = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                        + "<Volume channel=\"Master\" val=\"" + sounds.volume() + "\"/>"
                        + "<Mute channel=\"Master\" val=\"" + (sounds.muted() ? 1 : 0) + "\"/>"
                        + "</InstanceID></Event>";
                return DlnaXml.propertySet("LastChange", change);
            }
            default:
                return DlnaXml.propertySet("SourceProtocolInfo", "", "SinkProtocolInfo", DlnaXml.SINK_PROTOCOLS,
                        "CurrentConnectionIDs", "0");
        }
    }

    private static String val(String name, String value) {
        return "<" + name + " val=\"" + DlnaXml.esc(value) + "\"/>";
    }

    /** Sends one event. GENA uses its own NOTIFY method, which HttpURLConnection refuses, so this is a raw request. */
    private void sendEvent(String sid, Subscription sub, String body) {
        try {
            URL u = new URL(sub.callback);
            if (!"http".equals(u.getProtocol())) return;
            InetAddress host = InetAddress.getByName(u.getHost());
            if (!(host.isSiteLocalAddress() || host.isLinkLocalAddress() || host.isLoopbackAddress())) return;
            Socket s = new Socket();
            try {
                s.connect(new java.net.InetSocketAddress(host, u.getPort() < 0 ? 80 : u.getPort()), 3000);
                s.setSoTimeout(5000);
                byte[] b = body.getBytes(UTF8);
                String path = u.getFile().isEmpty() ? "/" : u.getFile();
                String head = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + u.getHost() + ":" + (u.getPort() < 0 ? 80 : u.getPort())
                        + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + sid
                        + "\r\nSEQ: " + (sub.seq++) + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
                OutputStream out = s.getOutputStream();
                out.write(head.getBytes(UTF8));
                out.write(b);
                out.flush();
                s.getInputStream().read(); // wait for the reply's first byte
            } finally {
                s.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "event to " + sub.callback + " failed: " + e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private static String soapArg(String body, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? DlnaXml.unesc(m.group(1)) : "";
    }

    /** H:MM:SS, as UPnP wants durations and positions. */
    static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static int parseTime(String t) {
        try {
            String[] p = t.trim().split(":");
            double sec = 0;
            for (String x : p) sec = sec * 60 + Double.parseDouble(x);
            return (int) (sec * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readBody(InputStream in, String contentLength) throws Exception {
        int len = contentLength == null ? 0 : Integer.parseInt(contentLength.trim());
        if (len <= 0) return "";
        if (len > 256 * 1024) throw new Exception("body too large");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (len > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, len));
            if (n < 0) break;
            b.write(buf, 0, n);
            len -= n;
        }
        return new String(b.toByteArray(), UTF8);
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
