package dev.r2hack.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Joins the home network (the saved network with the highest priority, i.e. the one
 * set up on this robot; credentials stay in wpa_supplicant.conf) and
 * falls back to the robot's own hotspot if that fails, so the agent is always reachable.
 * KitKat's hotspot API is hidden, hence the reflection. The hotspot's name and password come from
 * {@link Hotspot}: this robot's own password, or the factory settings in the pairing button's setup window.
 */
final class WifiFailover {
    static final String MODE_STATION = "station";
    static final String MODE_AP = "ap";

    private final WifiManager wm;
    private final SharedPreferences prefs;
    private volatile String state = "starting";
    private volatile long disconnectedSince;
    private volatile String homeSsid = "";
    private final Hotspot hotspot;

    WifiFailover(Context ctx, boolean passive) {
        wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        prefs = ctx.getSharedPreferences("wifi", Context.MODE_PRIVATE);
        hotspot = new Hotspot(ctx, wm, passive);
    }

    Hotspot hotspot() {
        return hotspot;
    }

    /**
     * Pairing button: the hotspot runs with its factory settings for ms, then goes back to this robot's
     * own password. A running hotspot is restarted for each switch (phones on it have to join again).
     */
    void factoryWindow(final long ms) {
        boolean wasOwn = !hotspot.usingFactory();
        hotspot.openFactoryWindow(ms);
        if (!hotspot.factoryDiffers()) return;
        if (wasOwn) {
            EventLog.i("hotspot: factory settings for " + ms / 1000 + " s (pairing button)");
            restartApLater(500);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(ms + 500);
                if (hotspot.usingFactory()) return; // extended by another press, or passive
                EventLog.i("hotspot: setup window over, back to this robot's password");
                restartAp();
            }
        }, "hotspot-window").start();
    }

    /** Restarts a running hotspot after delayMs so it picks up new settings; the HTTP reply goes out first. */
    void restartApLater(final long delayMs) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(delayMs);
                restartAp();
            }
        }, "hotspot-restart").start();
    }

    private synchronized void restartAp() {
        if (!isAp()) return; // the new settings apply the next time the hotspot comes up
        try {
            setAp(false);
            sleep(2000);
            setAp(true);
            EventLog.i("hotspot: restarted with " + (hotspot.usingFactory() ? "the factory settings" : "this robot's password"));
        } catch (Exception e) {
            EventLog.e("hotspot restart failed", e);
        }
    }

    String preferredMode() {
        return prefs.getString("mode", MODE_STATION);
    }

    String state() {
        return state;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "wifi");
        t.setDaemon(true);
        t.start();
    }

    /** Called from HTTP. The reply goes out before the network changes under it. */
    void request(final String mode) {
        prefs.edit().putString("mode", mode).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(1500);
                apply(mode);
            }
        }, "wifi-switch").start();
    }

    private void loop() {
        apply(preferredMode());
        while (true) {
            sleep(15_000);
            if (!MODE_STATION.equals(preferredMode()) || state.startsWith("ap")) continue;
            if (connectedToHome()) {
                disconnectedSince = 0;
            } else {
                long now = System.currentTimeMillis();
                if (disconnectedSince == 0) disconnectedSince = now;
                if (now - disconnectedSince > Config.STATION_LOST_TIMEOUT_MS) {
                    EventLog.i("wifi: lost " + homeSsid + ", falling back to hotspot");
                    goAp("ap (fallback)");
                }
            }
        }
    }

    private synchronized void apply(String mode) {
        if (MODE_AP.equals(mode)) {
            goAp("ap");
        } else if (goStation()) {
            state = "station " + homeSsid;
        } else {
            EventLog.i("wifi: could not join the home network, falling back to hotspot");
            goAp("ap (fallback)");
        }
    }

    private boolean goStation() {
        try {
            state = "joining home network";
            setAp(false);
            wm.setWifiEnabled(true);
            for (int i = 0; i < 30 && wm.getWifiState() != WifiManager.WIFI_STATE_ENABLED; i++) sleep(500);
            int id = findHomeNetwork();
            if (id < 0) {
                EventLog.i("wifi: no saved network to join");
                return false;
            }
            wm.enableNetwork(id, true);
            wm.reconnect();
            long deadline = System.currentTimeMillis() + Config.STATION_CONNECT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (connectedToHome() && ipAddress() != null) {
                    disconnectedSince = 0;
                    EventLog.i("wifi: joined " + homeSsid + " as " + ipAddress());
                    return true;
                }
                sleep(1000);
            }
        } catch (Exception e) {
            EventLog.e("wifi station failed", e);
        }
        return false;
    }

    private void goAp(String label) {
        try {
            wm.setWifiEnabled(false);
            sleep(1000);
            setAp(true);
            state = label;
            EventLog.i("wifi: hotspot on");
        } catch (Exception e) {
            state = "error";
            EventLog.e("wifi hotspot failed", e);
        }
    }

    private void setAp(boolean on) throws Exception {
        // Starting with a configuration also saves it as Android's hotspot settings (KitKat).
        WifiConfiguration cfg = hotspot.config();
        Method set = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
        set.invoke(wm, cfg, on);
    }

    /** Picks the saved network with the highest priority (Android raises it on each successful join). */
    private int findHomeNetwork() {
        List<WifiConfiguration> list = wm.getConfiguredNetworks();
        if (list == null) return -1;
        WifiConfiguration best = null;
        for (WifiConfiguration c : list) {
            if (c.SSID != null && (best == null || c.priority > best.priority)) best = c;
        }
        if (best == null) return -1;
        homeSsid = best.SSID.replace("\"", "");
        return best.networkId;
    }

    private boolean connectedToHome() {
        WifiInfo info = wm.getConnectionInfo();
        if (homeSsid.isEmpty() || info == null || info.getSSID() == null) return false;
        String ssid = info.getSSID().replace("\"", "");
        return homeSsid.equals(ssid) && info.getIpAddress() != 0;
    }

    boolean isAp() {
        return state.startsWith("ap");
    }

    /** The network we're joined to, or "" (e.g. in hotspot mode). */
    String currentSsid() {
        WifiInfo info = wm.getConnectionInfo();
        if (isAp() || info == null || info.getSSID() == null || info.getIpAddress() == 0) return "";
        return info.getSSID().replace("\"", "");
    }

    /** Networks seen in the last scan (kept from station mode, since scanning needs Wi-Fi on). */
    org.json.JSONArray scan() {
        org.json.JSONArray out = new org.json.JSONArray();
        try {
            if (!isAp()) wm.startScan();
            List<android.net.wifi.ScanResult> results = wm.getScanResults();
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (results != null) {
                for (android.net.wifi.ScanResult r : results) {
                    if (r.SSID == null || r.SSID.isEmpty() || !seen.add(r.SSID)) continue;
                    out.put(new org.json.JSONObject().put("ssid", r.SSID).put("rssi", r.level));
                }
            }
        } catch (Exception e) {
            EventLog.e("wifi scan failed", e);
        }
        return out;
    }

    /**
     * Saves a network as the new home network and joins it (phone-app Wi-Fi setup). If it can't be
     * joined, it's forgotten again and the previous network (or the hotspot) comes back.
     * Returns true when joined.
     */
    boolean join(String ssid, String password) {
        synchronized (this) {
            int max = 0;
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            if (list != null) for (WifiConfiguration c : list) max = Math.max(max, c.priority);
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + ssid + "\"";
            if (password == null || password.isEmpty()) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                c.preSharedKey = "\"" + password + "\"";
            }
            c.priority = max + 1;
            boolean wasAp = isAp();
            if (wasAp) {
                try {
                    setAp(false);
                } catch (Exception ignored) {
                }
                wm.setWifiEnabled(true);
                for (int i = 0; i < 30 && wm.getWifiState() != WifiManager.WIFI_STATE_ENABLED; i++) sleep(500);
            }
            int id = wm.addNetwork(c);
            if (id < 0) {
                EventLog.i("wifi: could not add network " + ssid);
                apply(preferredMode());
                return false;
            }
            wm.saveConfiguration();
            prefs.edit().putString("mode", MODE_STATION).apply();
            EventLog.i("wifi: joining new network " + ssid);
            if (goStation() && ssid.equals(homeSsid)) {
                state = "station " + homeSsid;
                return true;
            }
            EventLog.i("wifi: could not join " + ssid + ", forgetting it");
            wm.removeNetwork(id);
            wm.saveConfiguration();
            if (wasAp) prefs.edit().putString("mode", MODE_AP).apply();
            apply(preferredMode());
            return false;
        }
    }

    /** First non-loopback IPv4 address on any interface (station or hotspot). */
    static String ipAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
