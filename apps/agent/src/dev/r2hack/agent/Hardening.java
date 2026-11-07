package dev.r2hack.agent;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Boot hardening for Android 4.4 on the robot (docs/hardening.md). Everything is off until switched on,
 * and each part can be switched off again on its own:
 *
 * - firewall: iptables rules that only let the local network reach the ports R2 uses (HardeningRules).
 *   Switching it on starts a trial: unless POST /hardening/confirm arrives within TRIAL_MS (which
 *   proves the API is still reachable through the firewall), the rules are removed again by themselves.
 *   Only a confirmed firewall is set up at boot. A reboot always starts without it until the agent runs,
 *   and USB adb and the serial console are never affected.
 * - devices: removes world access from the UARTs, raw memory, the eMMC, I2C/SPI and Allwinner's
 *   sunxi_debug backdoor. The controller's UART goes to the agent's own user first, and is checked to
 *   still be readable and writable by the agent, otherwise it is put back. ttyS0 and the console UART
 *   are left alone. Device permissions reset at every reboot; the agent applies them again at start.
 * - bluetooth: keeps Bluetooth off (nothing in R2 uses it; the factory software leaves it off too).
 *
 * In passive mode (factory app enabled) nothing is applied, so the factory fallback is untouched.
 */
final class Hardening {
    static final long TRIAL_MS = 5 * 60_000L;
    private static final long CHECK_MS = 120_000;   // re-check that the firewall's INPUT jump is still there
    private static final String PREF_FIREWALL = "harden-firewall";
    private static final String PREF_DEVICES = "harden-devices";
    private static final String PREF_BLUETOOTH = "harden-bluetooth";
    private static final String PREF_BT_WAS_ON = "harden-bt-was-on";
    private static final String PREF_ORIGINAL = "harden-original-nodes";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File scriptDir;
    private volatile long trialUntil;            // elapsedRealtime, 0 = no trial
    private volatile boolean fw4Active, fw6Active;
    private volatile String fwState = "off";
    private volatile String fw6State = "off";
    private volatile JSONArray fixedNodes = new JSONArray(), failedNodes = new JSONArray();
    private volatile List<String> extraSubnets = new ArrayList<>();

    Hardening(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.scriptDir = new File(ctx.getFilesDir(), "hardening");
    }

    /** At agent start. passive: the factory app runs, so everything is undone and nothing applied. */
    void start(final boolean passive, List<String> subnets) {
        extraSubnets = new ArrayList<>(subnets);
        // Off the main thread: these are several root shell calls, and onCreate must return quickly.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (Hardening.this) {
                    if (passive) {
                        removeFirewall("agent is passive (factory app enabled)");
                        EventLog.i("hardening: skipped, agent is passive");
                        return;
                    }
                    if (firewallSetting()) applyFirewall("setting");
                    else removeFirewall(null); // leftovers of an unconfirmed trial before an agent restart
                    if (prefs.getBoolean(PREF_DEVICES, false)) fixDevices();
                    if (prefs.getBoolean(PREF_BLUETOOTH, false)) bluetoothOff();
                }
                long lastCheck = SystemClock.elapsedRealtime();
                while (true) {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        tick(lastCheck);
                        if (SystemClock.elapsedRealtime() - lastCheck >= CHECK_MS) lastCheck = SystemClock.elapsedRealtime();
                    } catch (Exception e) {
                        EventLog.e("hardening: check failed", e);
                    }
                }
            }
        }, "hardening");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void tick(long lastCheck) {
        long now = SystemClock.elapsedRealtime();
        if (trialUntil != 0 && now >= trialUntil) {
            trialUntil = 0;
            removeFirewall("trial not confirmed within " + TRIAL_MS / 60_000 + " min, firewall removed");
            return;
        }
        // Something (netd, a script) may flush INPUT; put the jump back if the firewall should be on.
        if ((fw4Active || fw6Active) && now - lastCheck >= CHECK_MS) {
            boolean missing4 = fw4Active && !hooked("iptables", HardeningRules.CHAIN4);
            boolean missing6 = fw6Active && !hooked("ip6tables", HardeningRules.CHAIN6);
            if (missing4 || missing6) applyFirewall("rules were gone, set up again");
        }
    }

    // ---- API ---------------------------------------------------------------------------------

    /** POST /hardening: firewall=1|0, devices=1|0, bluetooth=1|0 (1 = keep Bluetooth off), all=0. */
    synchronized String set(String firewall, String devices, String bluetooth, String all) {
        StringBuilder out = new StringBuilder();
        if ("0".equals(all)) {
            firewall = "0";
            devices = "0";
            bluetooth = "0";
        }
        if ("1".equals(firewall)) {
            if (firewallSetting()) {
                applyFirewall("API (already confirmed)");
                out.append("firewall on (confirmed earlier). ");
            } else if (applyFirewall("API, trial")) {
                trialUntil = SystemClock.elapsedRealtime() + TRIAL_MS;
                out.append("firewall on for a ").append(TRIAL_MS / 60_000)
                        .append(" min trial: POST /hardening/confirm to keep it, otherwise it removes itself. ");
            } else {
                out.append("firewall NOT on: ").append(fwState).append(". ");
            }
        } else if ("0".equals(firewall)) {
            prefs.edit().putBoolean(PREF_FIREWALL, false).commit();
            trialUntil = 0;
            removeFirewall("off (API)");
            out.append("firewall off. ");
        }
        if ("1".equals(devices)) {
            prefs.edit().putBoolean(PREF_DEVICES, true).commit();
            fixDevices();
            out.append("device permissions tightened: ").append(fixedNodes.length()).append(" fixed, ")
                    .append(failedNodes.length()).append(" failed. ");
        } else if ("0".equals(devices)) {
            prefs.edit().putBoolean(PREF_DEVICES, false).commit();
            restoreDevices();
            out.append("device permissions restored. ");
        }
        if ("1".equals(bluetooth)) {
            prefs.edit().putBoolean(PREF_BLUETOOTH, true).commit();
            out.append(bluetoothOff()).append(". ");
        } else if ("0".equals(bluetooth)) {
            prefs.edit().putBoolean(PREF_BLUETOOTH, false).commit();
            out.append(bluetoothRestore()).append(". ");
        }
        String r = out.toString().trim();
        return r.isEmpty() ? "nothing changed (use firewall=, devices=, bluetooth= or all=0)" : r;
    }

    /** POST /hardening/confirm: the request came through the firewall, so keep it, also after reboots. */
    synchronized String confirm() {
        if (!fw4Active) return "firewall is not on; nothing to confirm";
        boolean wasTrial = trialUntil != 0;
        trialUntil = 0;
        prefs.edit().putBoolean(PREF_FIREWALL, true).commit();
        EventLog.i("hardening: firewall confirmed" + (wasTrial ? "" : " (again)") + ", set up at every boot from now on");
        return "firewall kept; it is set up at every boot (POST /hardening?firewall=0 to undo)";
    }

    /** POST /clients/allow changed the extra subnets: the firewall lets them in too. */
    synchronized void onSubnetsChanged(List<String> subnets) {
        extraSubnets = new ArrayList<>(subnets);
        if (fw4Active || fw6Active) applyFirewall("extra subnets changed");
    }

    /** Short form for /status. */
    JSONObject summary() throws Exception {
        long left = trialSecondsLeft();
        return new JSONObject()
                .put("firewall", fw4Active ? (left > 0 ? "trial, " + left + " s left" : "on") : "off")
                .put("firewall_ipv6", fw6State)
                .put("devices", prefs.getBoolean(PREF_DEVICES, false))
                .put("bluetooth_off", prefs.getBoolean(PREF_BLUETOOTH, false));
    }

    /** GET /hardening: the full report, including what listens on the network and world-writable devices. */
    JSONObject report() throws Exception {
        JSONObject fw = new JSONObject()
                .put("setting", firewallSetting())
                .put("active", fw4Active)
                .put("state", fwState)
                .put("ipv6", fw6State)
                .put("trial_seconds_left", trialSecondsLeft())
                .put("tcp_ports", ports(HardeningRules.TCP_PORTS))
                .put("udp_ports", ports(HardeningRules.UDP_PORTS))
                .put("sources", new JSONArray(HardeningRules.sources(false, extraSubnets)))
                .put("sources_ipv6", new JSONArray(HardeningRules.sources(true, extraSubnets)));
        JSONObject dev = new JSONObject()
                .put("setting", prefs.getBoolean(PREF_DEVICES, false))
                .put("fixed", fixedNodes)
                .put("failed", failedNodes)
                .put("world_writable", worldWritable());
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        JSONObject blue = new JSONObject()
                .put("setting_off", prefs.getBoolean(PREF_BLUETOOTH, false))
                .put("present", bt != null)
                .put("on", bt != null && bt.isEnabled());
        return new JSONObject().put("firewall", fw).put("devices", dev).put("bluetooth", blue)
                .put("listeners", listeners());
    }

    private long trialSecondsLeft() {
        long t = trialUntil;
        return t == 0 ? 0 : Math.max(0, (t - SystemClock.elapsedRealtime()) / 1000);
    }

    private boolean firewallSetting() {
        return prefs.getBoolean(PREF_FIREWALL, false);
    }

    // ---- firewall ----------------------------------------------------------------------------

    /** Builds both chains. IPv4 is required; if it fails, nothing stays in place. IPv6 is best effort. */
    private boolean applyFirewall(String why) {
        String state = null, err = null;
        for (String s : new String[] {"state", "conntrack"}) {
            String out = runScript("fw4", HardeningRules.applyScript(false, s, extraSubnets));
            if (out.endsWith("OK")) {
                state = s;
                break;
            }
            err = out;
            runScript("fw4-off", HardeningRules.removeScript("iptables", HardeningRules.CHAIN4));
        }
        if (state == null) {
            fw4Active = false;
            fwState = "failed, nothing applied: " + lastLine(err);
            EventLog.i("hardening: firewall could not be set up (" + why + "): " + err);
            removeFirewall(null);
            return false;
        }
        fw4Active = true;
        fwState = "on (" + state + " match)";
        if (!new File("/system/bin/ip6tables").exists()) {
            fw6Active = false;
            fw6State = "not filtered: no ip6tables on this build";
        } else {
            String out = runScript("fw6", HardeningRules.applyScript(true, state, extraSubnets));
            if (out.endsWith("OK")) {
                fw6Active = true;
                fw6State = "on";
            } else {
                runScript("fw6-off", HardeningRules.removeScript("ip6tables", HardeningRules.CHAIN6));
                fw6Active = false;
                fw6State = "not filtered: " + lastLine(out);
            }
        }
        EventLog.i("hardening: firewall on (" + why + "), IPv6 " + fw6State);
        return true;
    }

    private void removeFirewall(String why) {
        runScript("fw4-off", HardeningRules.removeScript("iptables", HardeningRules.CHAIN4));
        if (new File("/system/bin/ip6tables").exists()) {
            runScript("fw6-off", HardeningRules.removeScript("ip6tables", HardeningRules.CHAIN6));
        }
        boolean was = fw4Active || fw6Active;
        fw4Active = false;
        fw6Active = false;
        fwState = "off";
        fw6State = "off";
        if (why != null && (was || !why.contains("passive"))) EventLog.i("hardening: " + why);
    }

    private boolean hooked(String ipt, String chain) {
        String out = Root.run(ipt + " -C INPUT -j " + chain + " && echo HOOKED");
        if (out.endsWith("HOOKED")) return true;
        // Only a clear "no such rule" counts as missing; an iptables without -C must not cause a loop of re-applies.
        return !(out.contains("matching rule") || out.contains("Bad rule") || out.contains("No chain"));
    }

    // ---- device nodes ------------------------------------------------------------------------

    private void fixDevices() {
        String cmdline = Supervisor.run("cat", "/proc/cmdline");
        int console = HardeningRules.consoleUart(cmdline);
        List<HardeningRules.Node> nodes = HardeningRules.parseNodes(
                Root.run(HardeningRules.listTargetsScript()));
        JSONObject original = originals();
        JSONArray fixed = new JSONArray(), failed = new JSONArray();
        String agentUser = HardeningRules.appUserName(android.os.Process.myUid());
        for (HardeningRules.Node n : nodes) {
            if (HardeningRules.skip(n, console)) continue;
            String mode = HardeningRules.octal(HardeningRules.tightened(n.mode));
            boolean controller = n.path.equals(Config.SERIAL_DEVICE);
            try {
                if (!original.has(n.path)) {
                    original.put(n.path, new JSONObject().put("mode", HardeningRules.octal(n.mode))
                            .put("owner", n.owner).put("group", n.group));
                    prefs.edit().putString(PREF_ORIGINAL, original.toString()).commit();
                }
            } catch (Exception ignored) {
            }
            if (controller) {
                // The agent opens the UART as its own user: give it the node first, then check it still can.
                if (agentUser == null) {
                    failed.put(n.path + ": agent uid " + android.os.Process.myUid() + " has no app user name, left as is");
                    continue;
                }
                String out = Root.run("chown " + agentUser + " " + n.path + " && chmod " + mode + " " + n.path);
                File f = new File(n.path);
                if (!f.canRead() || !f.canWrite()) {
                    Root.run("chown " + n.owner + "." + n.group + " " + n.path
                            + "; chmod " + HardeningRules.octal(n.mode) + " " + n.path);
                    failed.put(n.path + ": agent lost access after the change, put back (" + out + ")");
                    continue;
                }
                fixed.put(n.path + " " + mode + " " + agentUser + ":" + n.group);
            } else {
                String out = Root.run("chmod " + mode + " " + n.path + " && echo DONE");
                if (out.endsWith("DONE")) fixed.put(n.path + " " + mode + " " + n.owner + ":" + n.group);
                else failed.put(n.path + ": " + out);
            }
        }
        fixedNodes = fixed;
        failedNodes = failed;
        EventLog.i("hardening: device permissions: fixed " + fixed + (failed.length() > 0 ? ", failed " + failed : ""));
    }

    private void restoreDevices() {
        JSONObject original = originals();
        JSONArray names = original.names();
        StringBuilder sb = new StringBuilder();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String path = names.optString(i);
                JSONObject o = original.optJSONObject(path);
                if (o == null || !path.matches("/[A-Za-z0-9_./-]+")) continue;
                sb.append("[ -e ").append(path).append(" ] && { chown ").append(o.optString("owner")).append('.')
                        .append(o.optString("group")).append(' ').append(path).append("; chmod ")
                        .append(o.optString("mode")).append(' ').append(path).append("; }\n");
            }
        }
        sb.append("true\n");
        String out = runScript("devices-off", sb.toString());
        prefs.edit().remove(PREF_ORIGINAL).commit();
        fixedNodes = new JSONArray();
        failedNodes = new JSONArray();
        EventLog.i("hardening: device permissions restored" + (out.isEmpty() ? "" : " (" + out + ")"));
    }

    private JSONObject originals() {
        try {
            return new JSONObject(prefs.getString(PREF_ORIGINAL, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray worldWritable() {
        JSONArray a = new JSONArray();
        for (String dir : new String[] {"/dev", "/dev/block", "/dev/input", "/dev/snd", "/dev/graphics"}) {
            for (String s : HardeningRules.worldWritableDevices(dir, Supervisor.run("ls", "-l", dir))) a.put(s);
        }
        return a;
    }

    // ---- Bluetooth ---------------------------------------------------------------------------

    private String bluetoothOff() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) return "no Bluetooth on this robot";
        try {
            if (!bt.isEnabled()) return "Bluetooth already off, kept off";
            prefs.edit().putBoolean(PREF_BT_WAS_ON, true).commit();
            bt.disable();
            EventLog.i("hardening: Bluetooth switched off");
            return "Bluetooth switched off";
        } catch (Exception e) {
            EventLog.e("hardening: Bluetooth off failed", e);
            return "Bluetooth off failed: " + e.getMessage();
        }
    }

    private String bluetoothRestore() {
        boolean wasOn = prefs.getBoolean(PREF_BT_WAS_ON, false);
        prefs.edit().remove(PREF_BT_WAS_ON).commit();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (!wasOn || bt == null) return "Bluetooth left as it is";
        try {
            bt.enable();
            EventLog.i("hardening: Bluetooth switched back on");
            return "Bluetooth switched back on";
        } catch (Exception e) {
            return "Bluetooth on failed: " + e.getMessage();
        }
    }

    // ---- listeners ---------------------------------------------------------------------------

    private JSONArray listeners() throws Exception {
        JSONArray a = new JSONArray();
        PackageManager pm = ctx.getPackageManager();
        for (String proto : new String[] {"tcp", "tcp6", "udp", "udp6"}) {
            for (HardeningRules.Listener l : HardeningRules.parseProcNet(proto, readFile("/proc/net/" + proto))) {
                if (l.loopbackOnly()) continue;
                String who = pm.getNameForUid(l.uid);
                a.put(new JSONObject().put("proto", proto).put("address", l.address).put("port", l.port)
                        .put("uid", l.uid).put("app", who == null ? (l.uid == 0 ? "root" : "uid " + l.uid) : who)
                        .put("reachable_through_firewall", HardeningRules.allowed(l)));
            }
        }
        return a;
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Writes a script to the agent's files and runs it as root; returns its output. */
    private String runScript(String name, String script) {
        try {
            if (!scriptDir.isDirectory() && !scriptDir.mkdirs()) return "error: cannot create " + scriptDir;
            File f = new File(scriptDir, name + ".sh");
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(script.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return Root.run("sh " + f.getAbsolutePath());
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    private static String readFile(String path) {
        try {
            FileInputStream in = new FileInputStream(path);
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String lastLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = t.lastIndexOf('\n');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    private static JSONArray ports(int[] ports) {
        JSONArray a = new JSONArray();
        for (int p : ports) a.put(p);
        return a;
    }
}
