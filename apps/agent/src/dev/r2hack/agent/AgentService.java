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
public class AgentService extends Service implements SerialLink.Listener, Bridge.Handler,
        HttpApi.Routes, Supervisor.StopHook {

    private static boolean started;
    private final long bootAt = SystemClock.elapsedRealtime();
    private SerialLink serial;
    private Bridge bridge;
    private Supervisor supervisor;
    private WifiFailover wifi;
    private boolean passive;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (started) return;
        started = true;
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

        supervisor = new Supervisor(this, this);
        wifi = new WifiFailover(this);
        wifi.start();
        new HttpApi(this, supervisor.uploadDir()).start();
        startAnnouncer();
        if (passive) return;

        bridge = new Bridge(this);
        bridge.start();
        serial = new SerialLink(this, this);
        serial.start();
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
        bridge.broadcast(line);
    }

    @Override
    public void onAgentMessage(JSONObject msg) {
        String kind = msg.optString("agent");
        if ("heartbeat".equals(kind) || "hello".equals(kind)) supervisor.onHeartbeat();
    }

    @Override
    public boolean sendToController(String line) {
        return serial != null && serial.send(line);
    }

    @Override
    public void stopRobot() {
        sendToController("{\"cmd\":\"mode\",\"mode\":0}");
        EventLog.i("sent stop to controller");
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
            case "GET /ping":
                return HttpApi.Response.json(200, "{\"r2agent\":\"" + Secrets.VERSION + "\"}");
            case "GET /status":
                return HttpApi.Response.json(200, status().toString(2));
            case "GET /log":
                return HttpApi.Response.text(EventLog.dump());
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

    private JSONObject status() throws Exception {
        JSONObject s = new JSONObject();
        s.put("agent", Secrets.VERSION);
        s.put("uptime_s", (SystemClock.elapsedRealtime() - bootAt) / 1000);
        s.put("passive", passive);
        s.put("ip", WifiFailover.ipAddress());
        s.put("wifi", wifi.state());
        s.put("wifi_preferred", wifi.preferredMode());
        s.put("main_version", supervisor.mainVersion());
        s.put("main_alive", supervisor.mainAlive());
        s.put("main_probation", supervisor.onProbation());
        s.put("main_has_last_good", supervisor.hasLastGood());
        s.put("safe_mode", supervisor.safeMode());
        s.put("last_event", supervisor.lastEvent());
        s.put("stock_app_enabled", stockAppEnabled());
        if (serial != null) {
            s.put("serial_open", serial.isOpen());
            long rx = serial.lastRxAt();
            s.put("serial_last_rx_s_ago", rx == 0 ? -1 : (System.currentTimeMillis() - rx) / 1000);
            String gin = serial.lastGin();
            if (gin != null) s.put("last_gin", new JSONObject(gin));
            s.put("bridge_clients", bridge.clientCount());
        }
        return s;
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
