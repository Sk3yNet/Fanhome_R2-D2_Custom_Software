package dev.r2hack.agent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.0 server (no dependencies) for updates and control.
 * Each route needs a permission level (Permissions); a client proves who it is with a signed request
 * (RequestAuth) or, for read and control routes only, a plain token (Clients). See docs/security.md.
 */
final class HttpApi {
    interface Routes {
        Response handle(Request req) throws Exception;

        /** Routes that may be used without a token right now (e.g. Wi-Fi setup after the pairing button). */
        boolean openWithoutToken(Request req);

        /** Wall-clock unix seconds when the agent started, for RequestAuth. */
        long startedAtS();
    }

    static final class Request {
        String method;
        String path;
        /** Who sent it (null: no credentials), the level they get, and whether the request was signed. */
        Clients.Client client;
        String role = "";
        boolean signed;
        /** Body hash the signature covers; the body read must match it. */
        String claimedBodySha256;
        String bodySha256;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        File bodyFile;      // set for upload routes
        String bodyText;    // set for everything else (capped at 64 KB)
    }

    static final class Response {
        /** Writes a complete HTTP response itself, for streams (e.g. the camera). */
        interface Raw {
            void writeTo(OutputStream out) throws Exception;
        }

        final int code;
        final String type;
        final String body;
        Raw raw;

        static Response raw(Raw raw) {
            Response r = new Response(200, "", "");
            r.raw = raw;
            return r;
        }

        Response(int code, String type, String body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        static Response json(int code, String body) {
            return new Response(code, "application/json", body);
        }

        static Response html(String body) {
            return new Response(200, "text/html; charset=utf-8", body);
        }

        static Response text(String body) {
            return new Response(200, "text/plain; charset=utf-8", body);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_UPLOAD = 300L * 1024 * 1024;
    private final Routes routes;
    private final File uploadDir;
    private final Clients clients;
    private final RequestAuth auth = new RequestAuth();

    HttpApi(Routes routes, File uploadDir, Clients clients) {
        this.routes = routes;
        this.uploadDir = uploadDir;
        this.clients = clients;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (true) {
            try {
                ServerSocket server = new ServerSocket(Config.HTTP_PORT);
                server.setReuseAddress(true);
                EventLog.i("http listening on :" + Config.HTTP_PORT);
                while (true) {
                    final Socket s = server.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(s);
                        }
                    }, "http-conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Exception e) {
                EventLog.e("http server failed, retrying", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void serve(Socket s) {
        Response resp;
        Request req = null;
        try {
            s.setSoTimeout(120_000);
            if (!localClient(s.getInetAddress())) {
                EventLog.i("http: refused non-local client " + s.getInetAddress().getHostAddress());
                s.close();
                return;
            }
            if (!onOurSubnet(s.getInetAddress())) {
                refusedOffSubnet(s.getInetAddress());
                s.close();
                return;
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            req = parseHead(in);
            // The page itself is public; everything it does goes through protected routes.
            String need = Permissions.required(req.method, req.path);
            boolean open = Permissions.OPEN.equals(need) || routes.openWithoutToken(req);
            String authError = authenticate(req);
            if (!open && req.client == null) {
                resp = refused(401, authError != null ? authError
                        : "this route needs credentials: a signed request or X-R2-Token (docs/security.md)", s);
            } else if (!open && Clients.level(req.role) < Clients.level(need)) {
                resp = refused(403, "this needs " + need + " permission; " + req.client.name + " has " + req.role
                        + (req.signed || !Clients.ADMIN.equals(need) ? "" : " with a plain token (admin needs a signed request)"), s);
            } else {
                readBody(in, req);
                if (req.signed && !req.claimedBodySha256.equals(req.bodySha256)) {
                    resp = refused(401, "the body does not match X-R2-Body", s);
                } else {
                    if (req.client != null) clients.touch(req.client.id, req.method + " " + req.path);
                    resp = routes.handle(req);
                    audit(req, resp.code);
                }
            }
        } catch (Exception e) {
            EventLog.e("http request failed", e);
            resp = Response.json(500, "{\"error\":" + org.json.JSONObject.quote(String.valueOf(e)) + "}");
        } finally {
            if (req != null && req.bodyFile != null) req.bodyFile.delete();
        }
        if (resp.raw != null) {
            try {
                resp.raw.writeTo(s.getOutputStream());
            } catch (Exception ignored) {
                // viewer went away
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        try {
            byte[] body = resp.body.getBytes(UTF8);
            String head = "HTTP/1.0 " + resp.code + " " + (resp.code < 300 ? "OK" : "Error") + "\r\n"
                    + "Content-Type: " + resp.type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(UTF8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Works out who sent the request. Sets req.client, req.role and req.signed, or returns why the
     * credentials were refused; null with no client means none were presented.
     */
    private String authenticate(Request req) {
        String id = req.headers.get("x-r2-client");
        boolean inQuery = id == null && req.query.containsKey("r2client");
        if (inQuery) id = req.query.get("r2client");
        if (id != null) {
            Clients.Client c = clients.byId(id.trim());
            if (c == null) return "unknown client " + id.trim() + " (revoked?)";
            String time = inQuery ? req.query.get("r2time") : req.headers.get("x-r2-time");
            String nonce = inQuery ? req.query.get("r2nonce") : req.headers.get("x-r2-nonce");
            String sig = inQuery ? req.query.get("r2sig") : req.headers.get("x-r2-signature");
            // A signed link (an img tag) can't carry a body.
            String body = inQuery ? RequestAuth.EMPTY_SHA256 : req.headers.get("x-r2-body");
            long now = System.currentTimeMillis() / 1000;
            String err = auth.check(c.key, c.id, req.method, req.path, req.query, time, nonce, body, sig, now,
                    routes.startedAtS());
            if (err != null) return err;
            req.client = c;
            req.role = c.role;
            req.signed = true;
            req.claimedBodySha256 = body;
            return null;
        }
        String tok = presentedToken(req);
        if (tok == null || tok.isEmpty()) return null;
        Clients.Client c = clients.byPlainToken(tok);
        if (c != null) {
            req.client = c;
            // Plain tokens can be read off the network, so they never get more than control.
            req.role = Clients.level(c.role) > Clients.level(Clients.CONTROL) ? Clients.CONTROL : c.role;
            return null;
        }
        if (clients.isSigningOnlyKey(tok)) {
            return "this is the robot's owner key, which now has to sign requests instead of being sent as a token "
                    + "(docs/security.md). Update tools\\r2.ps1 / the web page, or make a token for Home Assistant: "
                    + "tools\\r2.ps1 client-add home-assistant -Plain";
        }
        return "wrong token";
    }

    private static Response refused(int code, String why, Socket s) {
        logRefusal(s.getInetAddress().getHostAddress(), code + " " + why);
        try {
            return Response.json(code, new org.json.JSONObject().put("error", why)
                    .put("time", System.currentTimeMillis() / 1000).toString());
        } catch (Exception e) {
            return Response.json(code, "{\"error\":\"refused\"}");
        }
    }

    /** Who did what: every request that changes something, except the joystick's stream of drive commands. */
    private static void audit(Request req, int code) {
        if ("GET".equals(req.method) || "/serial".equals(req.path)) return;
        String who = req.client == null ? "(no credentials)" : req.client.name + " [" + req.client.id + "]";
        EventLog.i("api: " + who + " " + req.method + " " + req.path + " -> " + code);
    }

    private static final Map<String, Long> lastAuthLog = new HashMap<>();

    /** At most one line a minute per address, so a polling page with a stale key can't flood the log. */
    private static void logRefusal(String ip, String what) {
        long now = System.currentTimeMillis();
        synchronized (lastAuthLog) {
            Long last = lastAuthLog.get(ip);
            if (last != null && now - last < 60_000) return;
            if (lastAuthLog.size() > 256) lastAuthLog.clear();
            lastAuthLog.put(ip, now);
        }
        EventLog.i("api: refused " + ip + ": " + what);
    }

    /**
     * The token from the X-R2-Token header, or for clients that can't set headers (an <img> tag,
     * Home Assistant's camera) the password of HTTP basic auth or ?token=.
     */
    private static String presentedToken(Request req) {
        String t = req.headers.get("x-r2-token");
        if (t != null) return t;
        String auth = req.headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String userPass = new String(android.util.Base64.decode(auth.substring(6).trim(), android.util.Base64.DEFAULT), UTF8);
                int c = userPass.indexOf(':');
                return c >= 0 ? userPass.substring(c + 1) : userPass;
            } catch (Exception ignored) {
            }
        }
        return req.query.get("token");
    }

    /** Private, link-local or loopback addresses only, so a forwarded port can't reach the API. */
    static boolean localClient(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress(); // IPv4-mapped (::ffff:a.b.c.d) is checked as IPv4
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
            if (mapped) {
                try {
                    a = InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
                } catch (Exception e) {
                    return false;
                }
            } else {
                return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                        || (b[0] & 0xfe) == 0xfc; // unique local fc00::/7
            }
        }
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    /**
     * Extra subnets allowed besides the robot's own (e.g. Home Assistant on another VLAN), as
     * {address bytes, prefix length}. Set from the stored setting (POST /clients/allow).
     */
    private static volatile List<Subnet> extraSubnets = Collections.emptyList();
    private static final Map<String, Long> lastRefusalLog = new HashMap<>();

    static final class Subnet {
        final byte[] net;
        final int prefix;

        Subnet(byte[] net, int prefix) {
            this.net = net;
            this.prefix = prefix;
        }

        boolean contains(byte[] a) {
            return a.length == net.length && samePrefix(a, net, prefix);
        }

        @Override
        public String toString() {
            try {
                return InetAddress.getByAddress(net).getHostAddress() + "/" + prefix;
            } catch (Exception e) {
                return "?/" + prefix;
            }
        }

        /** Parses "a.b.c.d/n" (IPv4 or IPv6); a bare address means a single host. */
        static Subnet parse(String text) throws Exception {
            String t = text.trim();
            int slash = t.indexOf('/');
            String addr = slash >= 0 ? t.substring(0, slash) : t;
            if (!addr.matches("[0-9a-fA-F:.]+")) throw new Exception("not an IP address: " + t); // no DNS lookups
            byte[] b = InetAddress.getByName(addr).getAddress();
            int max = b.length * 8;
            int p = slash >= 0 ? Integer.parseInt(t.substring(slash + 1).trim()) : max;
            if (p < 8 || p > max) throw new Exception("prefix length must be 8 to " + max + ": " + t);
            return new Subnet(b, p);
        }
    }

    static void setExtraSubnets(List<Subnet> subnets) {
        extraSubnets = Collections.unmodifiableList(new ArrayList<>(subnets));
    }

    static List<Subnet> extraSubnets() {
        return extraSubnets;
    }

    /**
     * The client must be on a subnet of one of the robot's own interfaces (Wi-Fi or its hotspot),
     * or loopback, or IPv6 link-local, or in an allowed extra subnet. A private address alone is not
     * enough: another private network routed to this one (a guest network, a VPN) is refused.
     */
    static boolean onOurSubnet(InetAddress a) {
        a = unmapped(a);
        if (a == null) return false;
        if (a.isLoopbackAddress() || (a instanceof Inet6Address && a.isLinkLocalAddress())) return true;
        byte[] client = a.getAddress();
        for (Subnet s : extraSubnets) {
            if (s.contains(client)) return true;
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress own = ia.getAddress();
                    if (own == null || own.isLoopbackAddress()) continue;
                    byte[] ours = own.getAddress();
                    if (ours.length != client.length) continue;
                    int prefix = ia.getNetworkPrefixLength();
                    // Some Android 4.x builds report nonsense for IPv4 (e.g. 64 or 0); assume a /24 then.
                    if (ours.length == 4 && (prefix <= 0 || prefix > 32)) prefix = 24;
                    if (ours.length == 16 && (prefix <= 0 || prefix > 128)) prefix = 64;
                    if (samePrefix(client, ours, prefix)) return true;
                }
            }
        } catch (Exception e) {
            EventLog.e("http: could not list network interfaces, allowing private client", e);
            return true; // localClient() already passed; don't lock everyone out on a platform quirk
        }
        return false;
    }

    static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        int full = prefix / 8, rest = prefix % 8;
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (b[full] & mask);
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) as IPv4; null if it can't be converted. */
    private static InetAddress unmapped(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a;
        byte[] b = a.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
        mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
        if (!mapped) return a;
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (Exception e) {
            return null;
        }
    }

    /** Logs a refused client at most once a minute per address, so a polling client can't flood the log. */
    private static void refusedOffSubnet(InetAddress a) {
        String ip = a.getHostAddress();
        long now = System.currentTimeMillis();
        synchronized (lastRefusalLog) {
            Long last = lastRefusalLog.get(ip);
            if (last != null && now - last < 60_000) return;
            if (lastRefusalLog.size() > 256) lastRefusalLog.clear();
            lastRefusalLog.put(ip, now);
        }
        EventLog.i("http: refused " + ip + ": not on the robot's own subnet (allow it with POST /clients/allow)");
    }

    private static Request parseHead(InputStream in) throws Exception {
        Request r = new Request();
        String requestLine = readLine(in);
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new Exception("bad request line");
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    r.query.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
        }
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        return r;
    }

    private void readBody(InputStream in, Request r) throws Exception {
        String cl = r.headers.get("content-length");
        long len = cl == null ? 0 : Long.parseLong(cl);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        if (len <= 0) {
            r.bodyText = "";
            r.bodySha256 = RequestAuth.hex(sha.digest());
            return;
        }
        if (r.path.endsWith("/install")) {
            if (len > MAX_UPLOAD) throw new Exception("upload too large");
            File f = new File(uploadDir, "upload-" + System.currentTimeMillis() + ".apk");
            OutputStream out = new FileOutputStream(f);
            try {
                byte[] buf = new byte[65536];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new Exception("upload truncated");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    left -= n;
                }
            } finally {
                out.close();
            }
            r.bodyFile = f;
            r.bodySha256 = RequestAuth.hex(sha.digest());
        } else {
            if (len > 65536) throw new Exception("body too large");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                b.write(buf, 0, n);
                left -= n;
            }
            byte[] body = b.toByteArray();
            r.bodySha256 = RequestAuth.hex(sha.digest(body));
            r.bodyText = new String(body, UTF8);
        }
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
