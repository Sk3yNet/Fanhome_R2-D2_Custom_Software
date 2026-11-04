package dev.r2hack.agent;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import org.json.JSONObject;

/**
 * Wires everything together. If the stock app is enabled again (factory fallback),
 * the agent stays passive: it keeps the update API up but leaves /dev/ttyS2 alone.
 */
public class AgentService extends Service implements ControllerLink.Listener, Bridge.Handler,
        HttpApi.Routes, Supervisor.StopHook {

    private static boolean started;
    private final long bootAt = SystemClock.elapsedRealtime();
    private ControllerLink serial;
    private MotionGuard guard;
    private Bridge bridge;
    private Supervisor supervisor;
    private WifiFailover wifi;
    private AdbControl adb;
    private boolean passive;
    private boolean sim;
    /** Setup window: opened by the pairing button; Wi-Fi setup and the token work without a token meanwhile. */
    private volatile long setupUntil;
    private volatile String lastJoin = "";
    private PowerManager.WakeLock wakeLock;
    /** What the main app runs ({"agent":"running","name":...,"last_routine":...}), for /status. */
    private volatile JSONObject running;
    private final MainRequests mainRequests = new MainRequests(new MainRequests.Sender() {
        @Override
        public boolean toMain(JSONObject msg) {
            return AgentService.this.toMain(msg);
        }
    });
    private static final long ROUTINE_REPLY_MS = 5_000;

    @Override
    public void onCreate() {
        super.onCreate();
        if (started) return;
        started = true;
        EventLog.init(getFilesDir());
        Credentials.init(this);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                EventLog.e("uncaught in " + t.getName(), e);
            }
        });
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("R2 Agent")
                .setContentText("update API on port " + Config.HTTP_PORT)
                .build());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "r2agent"); // field: an unreferenced lock is released by GC
        wakeLock.acquire();

        passive = stockAppEnabled();
        EventLog.i("agent " + Secrets.VERSION + " starting" + (passive ? " (PASSIVE: stock app enabled)" : ""));

        loadExtraSubnets();
        adb = new AdbControl(getSharedPreferences("agent", MODE_PRIVATE));
        adb.start();
        supervisor = new Supervisor(this, this);
        wifi = new WifiFailover(this, passive);
        wifi.start();
        new HttpApi(this, supervisor.uploadDir()).start();
        startAnnouncer();
        if (passive) return;

        bridge = new Bridge(this);
        bridge.start();
        sim = getSharedPreferences("agent", MODE_PRIVATE).getBoolean("sim", false);
        serial = sim ? new SimController(this) : new SerialLink(this, this);
        guard = new MotionGuard(new MotionGuard.Output() {
            @Override
            public boolean write(String line) {
                return serial.send(line);
            }
        });
        serial.start();
        guard.start();
        startControllerKeepalive();
        supervisor.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- serial / bridge -------------------------------------------------

    @Override
    public void onControllerLine(String line) {
        guard.onControllerLine(line);
        bridge.broadcast(line);
        if (line.contains("\"btn\"")) {
            try {
                int button = new JSONObject(line).optInt("value");
                if (button == 3) openSetup();
                // Handled here, not in the main app, so the power button works even if that app is hung.
                if (button == 1) {
                    if (sim) EventLog.i("sim: power button (not shutting down a simulated robot)");
                    else shutdown("power button");
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onAgentMessage(JSONObject msg) {
        String kind = msg.optString("agent");
        if ("heartbeat".equals(kind)) supervisor.onHeartbeat();
        else if ("hello".equals(kind)) {
            supervisor.onHeartbeat();
            sendMqttSettings(); // R2 Main keeps them in memory only
        }
        else if ("mqtt-status".equals(kind)) mqttState = msg.optString("state");
        else if ("shutdown".equals(kind)) shutdown("requested by the main app");
        else if ("settings".equals(kind)) settings = msg;
        else if ("running".equals(kind)) running = msg;
        else if ("routines-reply".equals(kind)) mainRequests.onReply(msg);
        else if ("wifi-scan".equals(kind)) {
            try {
                toMain(new JSONObject().put("agent", "wifi-list").put("seq", msg.optInt("seq"))
                        .put("list", wifi.scan()).put("current", wifi.currentSsid()));
            } catch (Exception e) {
                EventLog.e("wifi-scan reply failed", e);
            }
        } else if ("wifi-join".equals(kind)) {
            final String ssid = msg.optString("ssid"), pw = msg.optString("password");
            final int seq = msg.optInt("seq");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = wifi.join(ssid, pw);
                    try {
                        toMain(new JSONObject().put("agent", "wifi-join-result").put("seq", seq).put("ok", ok));
                    } catch (Exception ignored) {
                    }
                }
            }, "wifi-join").start();
        }
        else if ("wifi-toggle".equals(kind)) {
            String next = WifiFailover.MODE_AP.equals(wifi.preferredMode()) ? WifiFailover.MODE_STATION : WifiFailover.MODE_AP;
            EventLog.i("wifi button: switching to " + next);
            wifi.request(next);
        }
    }

    @Override
    public boolean sendToController(String line) {
        return guard != null && guard.send(line);
    }

    @Override
    public void stopRobot() {
        if (guard != null) guard.stop("agent stop");
    }

    /** Clean power-off: stop, flush storage, tell the controller, then shut Android down. */
    /** The main app's latest settings ({"agent":"settings","volume","muted","voice","face"}). */
    private volatile JSONObject settings;
    /** R2 Main's Home Assistant (MQTT) connection state. */
    private volatile String mqttState = "";

    private void shutdown(String why) {
        EventLog.i("shutting down: " + why);
        stopRobot();
        if (serial != null) serial.send("{\"cmd\":\"led\",\"r\":5,\"b\":1,\"y\":1,\"g\":1}"); // factory power-off lights
        Supervisor.run("sync");
        if (serial != null) serial.send("{\"cmd\":\"shut-down\"}");
        Intent i = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
        i.putExtra("android.intent.extra.KEY_CONFIRM", false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            // Granted only when the /system copy of the agent asks for it too; otherwise power off as root.
            EventLog.i("shutdown permission missing, powering off with su: " + e.getMessage());
            String out = Supervisor.run("su", "-c", "reboot -p");
            if (!out.isEmpty()) EventLog.i("power off: " + out);
        }
    }

    private void openSetup() {
        setupUntil = SystemClock.elapsedRealtime() + Config.SETUP_WINDOW_MS;
        EventLog.i("setup window open for " + Config.SETUP_WINDOW_MS / 1000 + " s (pairing button)");
        wifi.factoryWindow(Config.SETUP_WINDOW_MS); // the way back in when the hotspot password is forgotten
    }

    private long setupSecondsLeft() {
        return Math.max(0, (setupUntil - SystemClock.elapsedRealtime()) / 1000);
    }

    @Override
    public boolean openWithoutToken(HttpApi.Request req) {
        String route = req.method + " " + req.path;
        if ("GET /setup".equals(route)) return true; // only says whether the window is open
        boolean setupRoute = "GET /setup/token".equals(route) || "GET /wifi/scan".equals(route)
                || "POST /wifi/join".equals(route) || "GET /wifi/last-join".equals(route)
                || "GET /hotspot".equals(route);
        return setupRoute && setupSecondsLeft() > 0;
    }

    /** Joins a network chosen on the web page; the reply goes out before the hotspot goes away. */
    private void joinLater(final String ssid, final String password) {
        lastJoin = "joining " + ssid;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                boolean ok = wifi.join(ssid, password);
                lastJoin = ok ? "joined " + ssid + " as " + WifiFailover.ipAddress() : "could not join " + ssid
                        + " (wrong password or out of range); back on " + wifi.state();
                EventLog.i("wifi setup: " + lastJoin);
            }
        }, "wifi-setup-join").start();
    }

    /** Hands a routine request to the main app (Brain.onRoutinesRequest) and returns its answer. */
    private HttpApi.Response routines(JSONObject ask) throws Exception {
        if (passive) return passiveError();
        String op = ask.optString("op");
        if (("get".equals(op) || "delete".equals(op) || "run".equals(op) || "cue-get".equals(op) || "cue-delete".equals(op))
                && ask.optString("name").isEmpty()) {
            return bad("name= is required, e.g. ?name=" + (op.startsWith("cue") ? "a-new-hope" : "greet"));
        }
        JSONObject reply = mainRequests.ask(ask.put("agent", "routines"), ROUTINE_REPLY_MS);
        if (reply == null) return HttpApi.Response.json(503, "{\"error\":\"the main app is not connected or did not answer\"}");
        boolean ok = reply.optBoolean("ok");
        reply.remove("agent");
        reply.remove("seq");
        reply.remove("ok");
        return HttpApi.Response.json(ok ? 200 : 400, reply.toString(2));
    }

    /** Tells the main app over the bridge, e.g. to run an action. */
    private boolean toMain(JSONObject msg) {
        if (bridge == null || bridge.clientCount() == 0) return false;
        bridge.broadcast(msg.toString());
        return true;
    }

    /** Announce readiness once, then poll status like the stock app, whether or not the main app runs. */
    private void startControllerKeepalive() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean readySent = false;
                while (true) {
                    if (serial.isOpen()) {
                        if (!readySent) readySent = serial.send("{\"cmd\":\"ready\"}");
                        serial.send("{\"cmd\":\"gin\"}");
                    }
                    try { // network state for the main app (status lights, phone app)
                        toMain(new JSONObject().put("agent", "network").put("ap", wifi.isAp())
                                .put("ssid", wifi.currentSsid()).put("ip", String.valueOf(WifiFailover.ipAddress())));
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(Config.GIN_POLL_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "keepalive");
        t.setDaemon(true);
        t.start();
    }

    // ---- discovery -------------------------------------------------------

    private void startAnnouncer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Charset utf8 = Charset.forName("UTF-8");
                while (true) {
                    try {
                        DatagramSocket sock = new DatagramSocket();
                        sock.setBroadcast(true);
                        byte[] msg = ("{\"r2agent\":\"" + Secrets.VERSION + "\",\"ip\":\"" + WifiFailover.ipAddress()
                                + "\",\"port\":" + Config.HTTP_PORT + "}").getBytes(utf8);
                        sock.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"),
                                Config.ANNOUNCE_PORT));
                        sock.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "announce");
        t.setDaemon(true);
        t.start();
    }

    // ---- HTTP routes -----------------------------------------------------

    @Override
    public HttpApi.Response handle(HttpApi.Request req) throws Exception {
        String route = req.method + " " + req.path;
        switch (route) {
            case "GET /":
                return HttpApi.Response.html(asset("web-index.html"));
            case "GET /camera.mjpg":
            case "GET /camera.jpg":
                return HttpApi.Response.raw(proxyFromMain(req.path));
            case "GET /ping":
                return HttpApi.Response.json(200, "{\"r2agent\":\"" + Secrets.VERSION + "\"}");
            case "GET /status":
                return HttpApi.Response.json(200, status().toString(2));
            case "GET /log":
                return HttpApi.Response.text("1".equals(req.query.get("previous")) ? EventLog.dumpPrevious() : EventLog.dump());
            case "GET /logcat": // needs READ_LOGS (granted to priv-apps); ?lines=N&grep=text
                return HttpApi.Response.text(tail(Supervisor.run("logcat", "-d", "-v", "time"), req));
            case "GET /dmesg":
                return HttpApi.Response.text(tail(Supervisor.run("dmesg"), req));
            case "POST /main/install":
                if (passive) return passiveError();
                if (req.bodyFile == null) return bad("send the APK as the request body");
                return result(supervisor.installMain(req.bodyFile));
            case "POST /main/rollback":
                if (passive) return passiveError();
                return result(supervisor.rollback());
            case "POST /main/restart":
                if (passive) return passiveError();
                Supervisor.run("am", "force-stop", Config.MAIN_PACKAGE);
                supervisor.launchMain();
                return result("restarted");
            case "POST /agent/install": {
                if (req.bodyFile == null) return bad("send the APK as the request body");
                final File pending = new File(supervisor.uploadDir(), "agent-pending.apk");
                pending.delete();
                if (!req.bodyFile.renameTo(pending)) return bad("could not stage upload");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        String r = supervisor.installAgent(pending);
                        if (!r.contains("Success")) {
                            EventLog.i("agent install failed: " + r);
                            return;
                        }
                        // Updating over an earlier update kills this process, and MY_PACKAGE_REPLACED
                        // (BootReceiver) starts the new code. The first update over the /system copy
                        // leaves this process running on the old code path, so reboot to load it.
                        EventLog.i("agent install: Success, rebooting to load it");
                        stopRobot();
                        ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
                    }
                }).start();
                return result("installing; the agent restarts in a few seconds (or the robot reboots, ~60 s). "
                        + "If it never comes back: adb shell pm uninstall dev.r2hack.agent (reverts to the /system copy)");
            }
            case "POST /serial":
                if (passive) return passiveError();
                String line = req.bodyText.trim();
                new JSONObject(line); // reject non-JSON
                return result(sendToController(line) ? "sent" : "serial not open");
            case "POST /stop": // emergency stop: the agent stops the robot itself, then tells the main app
                if (passive) return passiveError();
                stopRobot();
                toMain(new JSONObject().put("agent", "action").put("name", "stop"));
                return result("stopped");
            case "POST /action": {
                if (passive) return passiveError();
                String name = req.query.get("name");
                if (name == null || name.isEmpty()) return bad("name= is required, e.g. /action?name=dance");
                return toMain(new JSONObject().put("agent", "action").put("name", name))
                        ? result("sent " + name + " to the main app") : bad("main app not connected");
            }
            case "POST /event": { // a named event for routines' "event" triggers (docs/routines.md)
                if (passive) return passiveError();
                String name = req.query.get("name");
                if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,31}")) {
                    return bad("name= is required: 1-32 characters, a-z, 0-9 and -, e.g. /event?name=doorbell");
                }
                return toMain(new JSONObject().put("agent", "event").put("name", name))
                        ? result("sent event " + name + " to the main app; see last_trigger in GET /routines")
                        : bad("main app not connected");
            }
            case "POST /assist": { // Home Assistant voice: mode=off|robot|ha and/or host=<Home Assistant's IP, or empty>
                String mode = req.query.get("mode");
                boolean hasHost = req.query.containsKey("host");
                if (mode == null ? !hasHost : !"off".equals(mode) && !"robot".equals(mode) && !"ha".equals(mode)) {
                    return bad("mode= must be off (default), robot (wake word on the robot) or ha (wake word in Home Assistant)");
                }
                JSONObject m = new JSONObject().put("agent", "assist").put("mode", mode == null ? "" : mode);
                if (hasHost) m.put("host", req.query.get("host"));
                return toMain(m) ? result("sent to the main app; see \"assist\" in GET /status") : bad("main app not connected");
            }
            case "GET /routines":
                return routines(new JSONObject().put("op", "list"));
            case "GET /routine":
                return routines(new JSONObject().put("op", "get").put("name", req.query.get("name")));
            case "POST /routine": { // body: the routine as JSON; ?name= optional (the body's name otherwise)
                if (passive) return passiveError();
                JSONObject body;
                try {
                    body = new JSONObject(req.bodyText);
                } catch (Exception e) {
                    return bad("the body must be the routine as a JSON object: " + e.getMessage());
                }
                JSONObject ask = new JSONObject().put("op", "put").put("routine", body);
                if (req.query.containsKey("name")) ask.put("name", req.query.get("name"));
                return routines(ask);
            }
            case "POST /routine/delete":
                if (passive) return passiveError();
                return routines(new JSONObject().put("op", "delete").put("name", req.query.get("name")));
            case "POST /routine/run":
                if (passive) return passiveError();
                return routines(new JSONObject().put("op", "run").put("name", req.query.get("name")));
            case "POST /routine/stop": // ends the running routine or action (POST /stop also stops the robot here)
                if (passive) return passiveError();
                return routines(new JSONObject().put("op", "stop"));
            // Watch-with-me (docs/watch-with-me.md): cue files and the session, handled by the main app.
            case "GET /cues":
                return routines(new JSONObject().put("op", "cue-list"));
            case "GET /cue":
                return routines(new JSONObject().put("op", "cue-get").put("name", req.query.get("name")));
            case "POST /cue": { // body: the cue file as JSON; ?name= optional
                if (passive) return passiveError();
                JSONObject body;
                try {
                    body = new JSONObject(req.bodyText);
                } catch (Exception e) {
                    return bad("the body must be the cue file as a JSON object: " + e.getMessage());
                }
                JSONObject ask = new JSONObject().put("op", "cue-put").put("cue", body);
                if (req.query.containsKey("name")) ask.put("name", req.query.get("name"));
                return routines(ask);
            }
            case "POST /cue/delete":
                if (passive) return passiveError();
                return routines(new JSONObject().put("op", "cue-delete").put("name", req.query.get("name")));
            case "GET /watch":
                return routines(new JSONObject().put("op", "watch"));
            case "POST /watch": { // body: {"state","position","title","cue","enabled"}
                if (passive) return passiveError();
                JSONObject body;
                try {
                    body = new JSONObject(req.bodyText == null || req.bodyText.trim().isEmpty() ? "{}" : req.bodyText);
                } catch (Exception e) {
                    return bad("the body must be a JSON object, e.g. {\"state\":\"playing\",\"cue\":\"a-new-hope\",\"position\":0}");
                }
                return routines(new JSONObject().put("op", "watch").put("watch", body));
            }
            case "POST /shutdown":
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        shutdown("API request");
                    }
                }).start();
                return result("shutting down");
            case "POST /sim": { // on=1|0, applies after an agent restart (POST /reboot)
                boolean on = "1".equals(req.query.get("on"));
                getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("sim", on).commit();
                return result("simulator " + (on ? "on" : "off") + " after a reboot (POST /reboot)");
            }
            case "POST /sim/button":
                if (!(serial instanceof SimController)) return bad("the simulator is not running");
                ((SimController) serial).pressButton(Integer.parseInt(req.query.get("value")));
                return result("pressed");
            case "POST /sim/battery":
                if (!(serial instanceof SimController)) return bad("the simulator is not running");
                ((SimController) serial).setBattery(Integer.parseInt(req.query.get("percent")),
                        req.query.containsKey("charging") ? Integer.parseInt(req.query.get("charging")) : 0);
                return result("battery set");
            case "GET /setup":
                return HttpApi.Response.json(200, new JSONObject().put("open", setupSecondsLeft() > 0)
                        .put("seconds_left", setupSecondsLeft()).toString());
            case "POST /setup/open": // the same window, for testing without pressing the button
                openSetup();
                return result("setup window open for " + Config.SETUP_WINDOW_MS / 1000 + " s");
            case "GET /setup/token":
                return HttpApi.Response.json(200, new JSONObject().put("token", Credentials.robotToken()).toString());
            case "GET /wifi/scan":
                return HttpApi.Response.json(200, new JSONObject().put("ap", wifi.isAp()).put("current", wifi.currentSsid())
                        .put("networks", wifi.scan()).toString());
            case "POST /wifi/join": {
                JSONObject j = new JSONObject(req.bodyText);
                String ssid = j.optString("ssid").trim();
                if (ssid.isEmpty() || ssid.length() > 32) return bad("ssid is required");
                String pw = j.optString("password");
                if (!pw.isEmpty() && (pw.length() < 8 || pw.length() > 63)) return bad("a Wi-Fi password has 8 to 63 characters");
                joinLater(ssid, pw);
                return result("joining " + ssid + " in ~2 s. Connect this device to " + ssid
                        + " too; if R2 can't join, its hotspot comes back within about a minute.");
            }
            case "GET /hotspot":
                return HttpApi.Response.json(200, hotspotInfo().toString(2));
            case "POST /hotspot": { // {"password":"..."} sets it, {} or "" makes a new random one
                JSONObject j = new JSONObject(req.bodyText.trim().isEmpty() ? "{}" : req.bodyText);
                String pw = j.optString("password");
                Hotspot h = wifi.hotspot();
                if (pw.isEmpty()) {
                    pw = Hotspot.generate(new java.security.SecureRandom());
                } else {
                    String why = Hotspot.problem(pw);
                    if (why != null) return bad(why);
                }
                h.setPassword(pw);
                EventLog.i("hotspot: password changed over the API");
                if (!h.usingFactory()) wifi.restartApLater(1500);
                JSONObject r = hotspotInfo().put("result", wifi.isAp() && !h.usingFactory()
                        ? "hotspot restarting in ~2 s; join " + h.ssid() + " again with the new password"
                        : "saved; used the next time the hotspot comes up");
                return HttpApi.Response.json(200, r.toString(2));
            }
            case "GET /wifi/last-join":
                return result(lastJoin.isEmpty() ? "no join attempted since the agent started" : lastJoin);
            case "POST /identity": { // the factory app's name, ID and paired phones, for the phone app
                JSONObject j = new JSONObject(req.bodyText);
                if (!toMain(j.put("agent", "identity"))) return bad("main app not connected");
                return result("identity sent to the main app");
            }
            case "POST /volume": { // ?percent=0..100: droid sounds and music
                int pct;
                try {
                    pct = Integer.parseInt(String.valueOf(req.query.get("percent")).trim());
                } catch (NumberFormatException e) {
                    return bad("percent must be 0 to 100");
                }
                if (pct < 0 || pct > 100) return bad("percent must be 0 to 100");
                if (!toMain(new JSONObject().put("agent", "volume").put("percent", pct))) return bad("main app not connected");
                return result("volume " + pct + "%");
            }
            case "POST /adb": { // on=1 [&minutes=N] for a while, always=1 across reboots, on=0 off
                if ("1".equals(req.query.get("always"))) return result(adb.setAlways());
                if (!"1".equals(req.query.get("on"))) return result(adb.disable());
                int minutes = Config.ADB_DEFAULT_MINUTES;
                if (req.query.containsKey("minutes")) {
                    try {
                        minutes = Integer.parseInt(req.query.get("minutes").trim());
                    } catch (NumberFormatException e) {
                        minutes = -1;
                    }
                }
                if (minutes < 1 || minutes > Config.ADB_MAX_MINUTES) {
                    return bad("minutes must be 1 to " + Config.ADB_MAX_MINUTES);
                }
                return result(adb.enable(minutes));
            }
            case "GET /clients/allow":
                return HttpApi.Response.json(200, new JSONObject().put("extra_subnets", subnetList()).toString());
            case "POST /clients/allow": { // ?subnets=10.0.20.0/24,192.168.5.10 (empty: only the robot's own subnet)
                String raw = req.query.get("subnets");
                java.util.List<HttpApi.Subnet> list = new java.util.ArrayList<>();
                if (raw != null) {
                    for (String part : raw.split(",")) {
                        if (part.trim().isEmpty()) continue;
                        try {
                            list.add(HttpApi.Subnet.parse(part));
                        } catch (Exception e) {
                            return bad(e.getMessage());
                        }
                    }
                }
                if (list.size() > 16) return bad("at most 16 extra subnets");
                HttpApi.setExtraSubnets(list);
                StringBuilder stored = new StringBuilder();
                for (HttpApi.Subnet n : list) stored.append(stored.length() == 0 ? "" : ",").append(n);
                getSharedPreferences("agent", MODE_PRIVATE).edit().putString("extra-subnets", stored.toString()).commit();
                EventLog.i("http: extra allowed subnets now [" + stored + "]");
                sendMqttSettings(); // the MQTT broker follows the same rule
                return result(list.isEmpty() ? "only clients on the robot's own subnet are accepted"
                        : "also accepting clients from " + stored);
            }
            case "GET /mqtt":
                return HttpApi.Response.json(200, mqttPublic().toString(2));
            case "POST /mqtt": { // {"host","port","username","password","prefix","enabled"}; {"host":""} turns it off
                JSONObject j = new JSONObject(req.bodyText.isEmpty() ? "{}" : req.bodyText);
                JSONObject old = mqttSettings();
                String host = j.optString("host", old.optString("host")).trim();
                if (host.length() > 253 || !host.matches("[A-Za-z0-9.:_\\-]*")) return bad("host must be a host name or IP address");
                int port = j.optInt("port", old.optInt("port", 1883));
                if (port < 1 || port > 65535) return bad("port must be 1 to 65535");
                String prefix = j.optString("prefix", old.optString("prefix", "homeassistant")).trim();
                if (prefix.isEmpty()) prefix = "homeassistant";
                if (prefix.length() > 64 || !prefix.matches("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*")) {
                    return bad("prefix may contain letters, digits, _ - and /");
                }
                String user = j.optString("username", old.optString("username"));
                // Leaving out the password keeps the stored one, so it never has to be shown again.
                String pw = j.has("password") ? j.optString("password") : old.optString("password");
                if (user.length() > 256 || pw.length() > 256) return bad("user name and password: at most 256 characters");
                boolean enabled = j.optBoolean("enabled", true) && !host.isEmpty();
                JSONObject stored = new JSONObject().put("host", host).put("port", port).put("username", user)
                        .put("password", pw).put("prefix", prefix).put("enabled", enabled);
                getSharedPreferences("agent", MODE_PRIVATE).edit().putString("mqtt", stored.toString()).commit();
                EventLog.i("mqtt: " + (enabled ? "broker " + host + ":" + port : "off"));
                boolean sent = sendMqttSettings();
                return result((enabled ? "Home Assistant over MQTT: broker " + host + ":" + port : "Home Assistant over MQTT off")
                        + (sent ? "; GET /mqtt shows the connection" : "; applies when the main app connects"));
            }
            case "POST /safe-mode":
                supervisor.setSafeMode("1".equals(req.query.get("on")));
                return result("safe mode " + (supervisor.safeMode() ? "on" : "off"));
            case "POST /wifi": {
                String mode = req.query.get("mode");
                if (!WifiFailover.MODE_STATION.equals(mode) && !WifiFailover.MODE_AP.equals(mode)) {
                    return bad("mode must be station or ap");
                }
                wifi.request(mode);
                return result("switching to " + mode + " in ~2s; reconnect via the new network");
            }
            case "POST /stock": {
                // enable=1: factory fallback (stock app back on, agent passive after reboot); enable=0: undo
                boolean enable = "1".equals(req.query.get("enable"));
                String r = Supervisor.run("pm", enable ? "enable" : "disable", Config.STOCK_PACKAGE);
                if (enable) wifi.hotspot().restoreFactory(); // the factory software gets its own hotspot back
                return result(r + " -- reboot to apply (POST /reboot)");
            }
            case "POST /reboot":
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
                    }
                }).start();
                return result("rebooting");
            default:
                return HttpApi.Response.json(404, "{\"error\":\"unknown route " + route + "\"}");
        }
    }

    /** Passes a camera response from R2 Main (localhost only) through to the authorized client. */
    private static HttpApi.Response.Raw proxyFromMain(final String path) {
        return new HttpApi.Response.Raw() {
            @Override
            public void writeTo(java.io.OutputStream out) throws Exception {
                java.net.Socket up = new java.net.Socket();
                try {
                    up.connect(new java.net.InetSocketAddress("127.0.0.1", Config.CAMERA_PORT), 2000);
                } catch (Exception e) {
                    out.write("HTTP/1.0 503 Unavailable\r\nContent-Type: text/plain\r\n\r\nthe main app's camera is not running\n"
                            .getBytes("UTF-8"));
                    return;
                }
                try {
                    up.setSoTimeout(10_000);
                    up.getOutputStream().write(("GET " + path + " HTTP/1.0\r\n\r\n").getBytes("UTF-8"));
                    java.io.InputStream in = up.getInputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    up.close();
                }
            }
        };
    }

    private String asset(String name) throws Exception {
        java.io.InputStream in = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** Last ?lines= lines (default 500, max 5000) of out, keeping only lines containing ?grep= if given. */
    private static String tail(String out, HttpApi.Request req) {
        int n = 500;
        try {
            n = Math.max(1, Math.min(5000, Integer.parseInt(req.query.get("lines"))));
        } catch (Exception ignored) {
        }
        String grep = req.query.get("grep");
        String[] all = out.split("\n");
        int from = all.length, kept = 0;
        while (from > 0 && kept < n) {
            from--;
            if (grep == null || all[from].contains(grep)) kept++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) {
            if (grep == null || all[i].contains(grep)) sb.append(all[i]).append('\n');
        }
        return sb.toString();
    }

    /** The hotspot's name and passwords (token or setup window only: this is how a forgotten one is found). */
    private JSONObject hotspotInfo() throws Exception {
        Hotspot h = wifi.hotspot();
        return new JSONObject().put("ssid", h.ssid()).put("password", h.password())
                .put("using", h.usingFactory() ? "factory" : "own")
                .put("factory_seconds_left", passive ? -1 : h.factorySecondsLeft())
                .put("factory_password", h.factoryPassword())
                .put("on", wifi.isAp());
    }

    private JSONObject status() throws Exception {
        JSONObject s = new JSONObject();
        s.put("agent", Secrets.VERSION);
        s.put("uptime_s", (SystemClock.elapsedRealtime() - bootAt) / 1000);
        s.put("passive", passive);
        s.put("simulated_controller", sim);
        s.put("ip", WifiFailover.ipAddress());
        s.put("wifi", wifi.state());
        s.put("wifi_preferred", wifi.preferredMode());
        s.put("hotspot", wifi.hotspot().ssid());
        s.put("hotspot_factory_settings", wifi.hotspot().usingFactory());
        s.put("main_version", supervisor.mainVersion());
        s.put("main_alive", supervisor.mainAlive());
        s.put("main_probation", supervisor.onProbation());
        s.put("main_has_last_good", supervisor.hasLastGood());
        s.put("safe_mode", supervisor.safeMode());
        s.put("last_event", supervisor.lastEvent());
        s.put("stock_app_enabled", stockAppEnabled());
        s.put("adb_wifi", adb.isOn());
        s.put("adb_wifi_always", adb.always());
        s.put("adb_wifi_seconds_left", adb.secondsLeft());
        s.put("extra_subnets", subnetList());
        s.put("mqtt", mqttState.isEmpty() ? "unknown" : mqttState);
        if (serial != null) {
            s.put("serial_open", serial.isOpen());
            long rx = serial.lastRxAt();
            s.put("serial_last_rx_s_ago", rx == 0 ? -1 : (System.currentTimeMillis() - rx) / 1000);
            String gin = serial.lastGin();
            if (gin != null) s.put("last_gin", new JSONObject(gin));
            s.put("bridge_clients", bridge.clientCount());
            s.put("moving", guard.moving());
            s.put("battery_low", guard.lowBattery());
        }
        JSONObject set = settings;
        if (set != null) {
            s.put("volume", set.optInt("volume"));
            s.put("muted", set.optBoolean("muted"));
            s.put("voice", set.optBoolean("voice"));
            s.put("face_tracking", set.optBoolean("face"));
            s.put("assist", set.optString("assist"));
        }
        JSONObject run = running;
        if (run != null && supervisor.mainAlive()) {
            s.put("running", run.optString("name", "idle"));
            s.put("last_routine", run.optString("last_routine"));
        }
        return s;
    }

    /** MQTT broker settings for R2 Main's Home Assistant link, kept here so R2 Main updates don't lose them. */
    private JSONObject mqttSettings() {
        try {
            return new JSONObject(getSharedPreferences("agent", MODE_PRIVATE).getString("mqtt", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The settings without the password, plus R2 Main's connection state. */
    private JSONObject mqttPublic() throws Exception {
        JSONObject m = mqttSettings();
        boolean hasPw = !m.optString("password").isEmpty();
        m.remove("password");
        return m.put("has_password", hasPw).put("state", mqttState.isEmpty() ? "unknown (main app not connected)" : mqttState);
    }

    private boolean sendMqttSettings() {
        try {
            return toMain(mqttSettings().put("agent", "mqtt").put("extra_subnets", subnetList()));
        } catch (Exception e) {
            return false;
        }
    }

    private void loadExtraSubnets() {
        String stored = getSharedPreferences("agent", MODE_PRIVATE).getString("extra-subnets", "");
        java.util.List<HttpApi.Subnet> list = new java.util.ArrayList<>();
        for (String part : stored.split(",")) {
            if (part.trim().isEmpty()) continue;
            try {
                list.add(HttpApi.Subnet.parse(part));
            } catch (Exception e) {
                EventLog.i("http: ignoring stored subnet " + part + ": " + e.getMessage());
            }
        }
        HttpApi.setExtraSubnets(list);
    }

    private static org.json.JSONArray subnetList() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (HttpApi.Subnet n : HttpApi.extraSubnets()) a.put(n.toString());
        return a;
    }

    private boolean stockAppEnabled() {
        try {
            int st = getPackageManager().getApplicationEnabledSetting(Config.STOCK_PACKAGE);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (IllegalArgumentException notInstalled) {
            return false;
        }
    }

    private static HttpApi.Response result(String msg) {
        return HttpApi.Response.json(200, "{\"result\":" + JSONObject.quote(msg) + "}");
    }

    private static HttpApi.Response bad(String msg) {
        return HttpApi.Response.json(400, "{\"error\":" + JSONObject.quote(msg) + "}");
    }

    private static HttpApi.Response passiveError() {
        return bad("agent is passive because the stock app is enabled; POST /stock?enable=0 then /reboot");
    }
}
