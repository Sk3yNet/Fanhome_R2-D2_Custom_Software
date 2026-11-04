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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.0 server (no dependencies) for updates and control.
 * Every route except GET /ping and the control page (GET /) needs the header  X-R2-Token: <token>.
 */
final class HttpApi {
    interface Routes {
        Response handle(Request req) throws Exception;

        /** Routes that may be used without a token right now (e.g. Wi-Fi setup after the pairing button). */
        boolean openWithoutToken(Request req);
    }

    static final class Request {
        String method;
        String path;
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

    HttpApi(Routes routes, File uploadDir) {
        this.routes = routes;
        this.uploadDir = uploadDir;
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
            // The page itself is public; everything it does goes through token-protected routes.
            boolean open = ("GET".equals(req.method) && ("/ping".equals(req.path) || "/".equals(req.path)))
                    || routes.openWithoutToken(req);
            if (!open && !Credentials.accepts(presentedToken(req))) {
                resp = Response.json(401, "{\"error\":\"missing or wrong X-R2-Token\"}");
            } else {
                readBody(in, req);
                resp = routes.handle(req);
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
        if (len <= 0) {
            r.bodyText = "";
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
                    left -= n;
                }
            } finally {
                out.close();
            }
            r.bodyFile = f;
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
            r.bodyText = new String(b.toByteArray(), UTF8);
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
