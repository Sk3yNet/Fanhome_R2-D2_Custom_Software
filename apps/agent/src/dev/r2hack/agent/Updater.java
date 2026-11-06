package dev.r2hack.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Updates both apps from the GitHub releases of one repository (docs/security.md). It downloads
 * R2Main.apk and R2Agent.apk from the latest release, refuses them unless they are signed by a trusted
 * certificate (ApkTrust) and carry the right package name and a newer versionCode, then installs R2 Main
 * (on probation, so a crash loop rolls it back) and then the agent (which reboots the robot).
 * Off by default; POST /update/auto?on=1 checks every few hours and installs only while the robot is idle.
 * With a mirror set (POST /update/mirror, docs/update-mirror.md) it reads the same releases from a copy on
 * the local network instead, for robots whose old TLS can't reach GitHub; the checks are the same.
 */
final class Updater {
    interface Host {
        boolean idle();

        /** Installs a verified agent APK in the background (the robot reboots). */
        String installAgentLater(File apk);
    }

    static final String DEFAULT_REPO = "Sk3yNet/Fanhome_R2-D2_Custom_Software";
    private static final long AUTO_EVERY_MS = 6 * 3600_000L;
    private static final long MAX_APK = 64L * 1024 * 1024;

    private final Context ctx;
    private final Supervisor supervisor;
    private final ApkTrust trust;
    private final Host host;
    private final SharedPreferences prefs;
    private volatile String lastResult = "never checked";
    private volatile long lastCheck;
    private volatile JSONObject latest;
    private volatile boolean busy;

    Updater(Context ctx, Supervisor supervisor, ApkTrust trust, Host host) {
        this.ctx = ctx;
        this.supervisor = supervisor;
        this.trust = trust;
        this.host = host;
        this.prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    String repo() {
        String built = Secrets.UPDATE_REPO.isEmpty() ? DEFAULT_REPO : Secrets.UPDATE_REPO;
        return prefs.getString("repo", built);
    }

    static boolean validRepo(String repo) {
        return repo != null && repo.matches("[A-Za-z0-9_.\\-]{1,100}/[A-Za-z0-9_.\\-]{1,100}") && !repo.contains("..");
    }

    void setRepo(String repo) {
        prefs.edit().putString("repo", repo).commit();
        latest = null;
        EventLog.i("update: following releases of " + repo);
    }

    /** The local release mirror (UpdateMirror), or "" to ask GitHub. */
    String mirror() {
        return prefs.getString("mirror", "");
    }

    void setMirror(String url) {
        prefs.edit().putString("mirror", url).commit();
        latest = null;
        EventLog.i("update: " + (url.isEmpty() ? "asking GitHub for releases" : "reading releases from the mirror " + url));
    }

    boolean auto() {
        return prefs.getBoolean("auto", false);
    }

    void setAuto(boolean on) {
        prefs.edit().putBoolean("auto", on).commit();
        EventLog.i("update: automatic updates " + (on ? "on" : "off"));
    }

    JSONObject status() throws Exception {
        JSONObject s = new JSONObject().put("repo", repo()).put("mirror", mirror()).put("auto", auto()).put("busy", busy)
                .put("last_check", lastCheck / 1000).put("last_result", lastResult)
                .put("main_installed", supervisor.mainVersion()).put("agent_installed", supervisor.version(ctx.getPackageName()));
        JSONObject l = latest;
        if (l != null) s.put("latest", l);
        return s;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10 * 60_000L); // let the robot settle after boot
                } catch (InterruptedException ignored) {
                }
                while (true) {
                    if (auto()) {
                        try {
                            JSONObject l = check();
                            if (l.optBoolean("update_available") && host.idle()) install();
                        } catch (Exception e) {
                            lastResult = "automatic check failed: " + e.getMessage();
                            EventLog.i("update: " + lastResult);
                        }
                    }
                    try {
                        Thread.sleep(AUTO_EVERY_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "updater");
        t.setDaemon(true);
        t.start();
    }

    /** Asks GitHub (or the mirror) for the latest release and says what would be installed. */
    synchronized JSONObject check() throws Exception {
        lastCheck = System.currentTimeMillis();
        String mirror = mirror();
        if (!mirror.isEmpty()) {
            String url = UpdateMirror.manifestUrl(mirror, repo());
            JSONObject out;
            try {
                out = UpdateMirror.parse(new String(get(url, 1024 * 1024, null, true), "UTF-8"), url, repo(), MAX_APK);
            } catch (Exception e) {
                throw new Exception("mirror " + mirror + ": " + e.getMessage());
            }
            boolean newerMain = UpdateMirror.newer(out, "main", versionName(Config.MAIN_PACKAGE), versionCode(Config.MAIN_PACKAGE));
            boolean newerAgent = UpdateMirror.newer(out, "agent", versionName(ctx.getPackageName()), versionCode(ctx.getPackageName()));
            if (!newerMain) out.put("main_url", "");
            if (!newerAgent) out.put("agent_url", "");
            String version = out.optString("version");
            out.put("update_available", newerMain || newerAgent);
            latest = out;
            lastResult = (newerMain || newerAgent) ? "release " + version + " available on the mirror" : "up to date (" + version + ", from the mirror)";
            return out;
        }
        JSONObject rel = new JSONObject(new String(get("https://api.github.com/repos/" + repo() + "/releases/latest",
                1024 * 1024, null, false), "UTF-8"));
        JSONObject out = new JSONObject().put("tag", rel.optString("tag_name")).put("published", rel.optString("published_at"))
                .put("url", rel.optString("html_url"));
        JSONArray assets = rel.optJSONArray("assets");
        String mainUrl = null, agentUrl = null;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if ("R2Main.apk".equals(a.optString("name"))) mainUrl = a.optString("browser_download_url");
            if ("R2Agent.apk".equals(a.optString("name"))) agentUrl = a.optString("browser_download_url");
        }
        out.put("main_url", mainUrl == null ? "" : mainUrl).put("agent_url", agentUrl == null ? "" : agentUrl);
        // The release's version name is the tag without the v; the versionCode is only known after download.
        String version = rel.optString("tag_name").replaceFirst("^v", "");
        boolean newerMain = mainUrl != null && !version.equals(versionName(Config.MAIN_PACKAGE));
        boolean newerAgent = agentUrl != null && !version.equals(versionName(ctx.getPackageName()));
        out.put("version", version).put("update_available", newerMain || newerAgent);
        latest = out;
        lastResult = (newerMain || newerAgent) ? "release " + version + " available" : "up to date (" + version + ")";
        return out;
    }

    /** Downloads, verifies and installs what is newer. Returns a summary. */
    String install() throws Exception {
        if (busy) return "an update is already running";
        busy = true;
        try {
            JSONObject l = check();
            if (!l.optBoolean("update_available")) return lastResult;
            List<String> done = new ArrayList<>();
            File agentApk = null;
            if (!l.optString("main_url").isEmpty()) {
                File f = new File(supervisor.uploadDir(), "update-main.apk");
                String why = download(l, "main", f);
                if (why == null) why = verify(f, Config.MAIN_PACKAGE, true);
                if (why != null) done.add("R2 Main: " + why);
                else done.add("R2 Main: " + supervisor.installMain(f));
                f.delete();
            }
            if (!l.optString("agent_url").isEmpty()) {
                File f = new File(supervisor.uploadDir(), "update-agent.apk");
                String why = download(l, "agent", f);
                if (why == null) why = verify(f, ctx.getPackageName(), true);
                if (why != null) {
                    done.add("R2 Agent: " + why);
                    f.delete();
                } else {
                    agentApk = f;
                }
            }
            if (agentApk != null) done.add("R2 Agent: " + host.installAgentLater(agentApk));
            lastResult = l.optString("version") + ": " + join(done);
            EventLog.i("update: " + lastResult);
            return lastResult;
        } finally {
            busy = false;
        }
    }

    /**
     * null if the APK may be installed as pkg: trusted signer, right package and, for updates, a newer
     * versionCode than the installed one. Also used for APKs uploaded over the API.
     */
    String verify(File apk, String pkg, boolean mustBeNewer) {
        String why = trust.refusal(apk);
        if (why != null) return why;
        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null) return "not an APK Android can read";
        if (!pkg.equals(pi.packageName)) return "this APK is " + pi.packageName + ", not " + pkg;
        if (mustBeNewer) {
            int installed = versionCode(pkg);
            if (installed >= 0 && pi.versionCode <= installed) {
                return "not newer than the installed build (versionCode " + pi.versionCode + " <= " + installed + ")";
            }
        }
        return null;
    }

    private String versionName(String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int versionCode(String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** Downloads the "main" or "agent" APK of check()'s result; null, or why the download is refused. */
    private String download(JSONObject l, String key, File to) throws Exception {
        to.delete();
        boolean fromMirror = l.has("mirror");
        get(l.optString(key + "_url"), MAX_APK, to, fromMirror);
        to.setReadable(true, false); // the package manager reads it as another user
        String sha = l.optString(key + "_sha256");
        if (!sha.isEmpty()) {
            if (to.length() != l.optLong(key + "_size")) return "the download from the mirror has the wrong size";
            if (!sha.equals(sha256(to))) return "the download from the mirror doesn't match its SHA-256";
        }
        return null;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * GET over https, following up to 5 redirects (release downloads redirect to GitHub's file host).
     * Plain http only for the local mirror (lan), whose files are checked by signature anyway.
     * Returns the body, or writes it to `to` if given.
     */
    private static byte[] get(String url, long max, File to, boolean lan) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            boolean https = "https".equals(u.getProtocol());
            if (!https && !(lan && "http".equals(u.getProtocol()))) throw new Exception("refusing a non-https URL: " + url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            if (https) ((HttpsURLConnection) c).setSSLSocketFactory(Tls12.factory());
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "r2-agent/" + Secrets.VERSION);
            c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = c.getHeaderField("Location");
                    if (next == null) throw new Exception("redirect without a Location");
                    url = new URL(u, next).toString();
                    continue;
                }
                if (code == 404) throw new Exception(lan ? "not found: " + u + " (has the mirror fetched this repository yet?)"
                        : "not found (no release yet, or a private repository): " + u);
                if (code != 200) throw new Exception("HTTP " + code + " from " + u.getHost());
                long len = c.getContentLength();
                if (len > max) throw new Exception("download too large");
                InputStream in = c.getInputStream();
                OutputStream out = to != null ? new FileOutputStream(to) : new ByteArrayOutputStream();
                try {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > max) throw new Exception("download too large");
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                return to != null ? null : ((ByteArrayOutputStream) out).toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() == 0 ? "" : "; ").append(p);
        return sb.toString();
    }

    /**
     * Android 4.4 supports TLS 1.2 but leaves it off by default, and GitHub accepts nothing older.
     * The certificate checks stay the system's own.
     */
    static final class Tls12 extends SSLSocketFactory {
        private static Tls12 instance;
        private final SSLSocketFactory base;

        static synchronized Tls12 factory() throws Exception {
            if (instance == null) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, null, null);
                instance = new Tls12(ctx.getSocketFactory());
            }
            return instance;
        }

        private Tls12(SSLSocketFactory base) {
            this.base = base;
        }

        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                List<String> want = new ArrayList<>();
                List<String> supported = Arrays.asList(ssl.getSupportedProtocols());
                for (String p : new String[] {"TLSv1.3", "TLSv1.2"}) if (supported.contains(p)) want.add(p);
                if (!want.isEmpty()) ssl.setEnabledProtocols(want.toArray(new String[0]));
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return on(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            return on(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws java.io.IOException {
            return on(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return on(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws java.io.IOException {
            return on(base.createSocket(address, port, local, localPort));
        }
    }
}
