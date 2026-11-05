package dev.r2hack.main;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Off-robot tests for the plugin interface: permissions, lifecycle, settings, events, and the two
 * built-in plugins. No test framework: run with apps/test.sh.
 */
public final class PluginTest {
    private static int passed, failed;

    /** A pretend R2 Main: records every call that got past the permission checks. */
    static final class FakeHost implements PluginContext.Host {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        volatile int battery = 80, charging;

        public JSONObject state() {
            try {
                return new JSONObject().put("battery", battery).put("charging", charging).put("action", "idle");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public boolean isAction(String name) {
            return Arrays.asList("stop", "dance", "lightsaber", "leia", "mute", "unmute", "voice-on", "voice-off",
                    "face-on", "face-off", "liveliness-full", "go-home", "pair", "phone-app-on", "phone-app-off").contains(name);
        }

        public void action(String name) { calls.add("action " + name); }
        public void stop() { calls.add("stop"); }
        public void projector(int mode) { calls.add("projector " + mode); }
        public List<String> routineNames() { return Arrays.asList("greet"); }
        public String runRoutine(String name) { calls.add("routine " + name); return null; }
        public String saveRoutine(JSONObject r) { calls.add("save " + r.optString("name")); return "saved"; }
        public String deleteRoutine(String name) { calls.add("delete " + name); return null; }
        public void controller(JSONObject cmd) { calls.add("cmd " + cmd.optString("cmd")); }
        public void playSound(int n) { calls.add("sound " + n); }
        public void setVolume(int p) { calls.add("volume " + p); }
        public void led(int r, int b, int y, int g) { calls.add("led"); }
        public List<String> placeNames() { return Arrays.asList("kitchen"); }
        public void goTo(String place) { calls.add("go " + place); }
        public String watchUpdate(JSONObject msg) { calls.add("watch " + msg.optString("state")); return "ok"; }
        public void setWatch(boolean on) { calls.add("watch-switch " + on); }
        public void routineEvent(String name, String from) { calls.add("event " + name + " from " + from); }
        public java.util.Set<String> triggerTopics() { return java.util.Collections.singleton("z/door"); }
        public void triggerMessage(String topic, String payload, boolean retained) { calls.add("mqtt " + topic); }
        public void log(String msg) { logs.add(msg); }
    }

    /** A plugin that records its lifecycle and events, and can be told to throw. */
    static final class TestPlugin implements Plugin {
        final String name;
        final Set<String> perms;
        final boolean byDefault;
        final List<String> seen = new CopyOnWriteArrayList<>();
        volatile PluginContext ctx;
        volatile boolean throwOnEvent, throwOnStart;
        volatile JSONObject config;

        TestPlugin(String name, boolean byDefault, String... perms) {
            this.name = name;
            this.byDefault = byDefault;
            this.perms = new TreeSet<>(Arrays.asList(perms));
        }

        public String name() { return name; }
        public String description() { return "test plugin"; }
        public Set<String> permissions() { return perms; }
        public boolean enabledByDefault() { return byDefault; }

        public void start(PluginContext c, JSONObject cfg) throws Exception {
            if (throwOnStart) throw new Exception("start failed on purpose");
            ctx = c;
            config = cfg;
            seen.add("start");
        }

        public void configure(JSONObject cfg) { config = cfg; seen.add("configure"); }

        public void onEvent(String type, JSONObject data) {
            if (throwOnEvent) throw new IllegalStateException("event failed on purpose");
            seen.add(type);
        }

        public void stop() { seen.add("stop"); }
        public String status() { return "fine"; }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(String what, Object want, Object got) {
        check(what + " (want " + want + ", got " + got + ")", want == null ? got == null : want.equals(got));
    }

    static void contains(String what, String text, String part) {
        check(what + " (\"" + text + "\" should contain \"" + part + "\")", text != null && text.contains(part));
    }

    static void denied(String what, Runnable r) {
        try {
            r.run();
            check(what + " should be refused", false);
        } catch (PluginContext.Denied d) {
            passed++;
        }
    }

    public static void main(String[] args) throws Exception {
        permissions();
        lifecycle();
        grants();
        events();
        failures();
        config();
        batteryAlert();
        homeAssistant();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static File tempFile() throws Exception {
        File dir = Files.createTempDirectory("r2plugins").toFile();
        dir.deleteOnExit();
        return new File(dir, "plugins.json");
    }

    static PluginManager.ConfigStore noStore() {
        return new PluginManager.ConfigStore() {
            public void save(String plugin, JSONObject config) {
            }
        };
    }

    static JSONObject ask(PluginManager m, String op, String name) throws Exception {
        return m.request(new JSONObject().put("op", op).put("name", name));
    }

    static void permissions() throws Exception {
        FakeHost h = new FakeHost();
        final PluginContext none = new PluginContext("p", h, Collections.<String>emptySet(), noStore());
        denied("state without status", new Runnable() { public void run() { none.state(); } });
        denied("action without actions", new Runnable() { public void run() { none.action("dance"); } });
        denied("sound without sound", new Runnable() { public void run() { none.playSound(1); } });
        denied("drive without drive", new Runnable() { public void run() { none.drive(40, 0); } });
        denied("routine without routines.run", new Runnable() { public void run() { none.runRoutine("greet"); } });
        denied("volume without settings", new Runnable() { public void run() { none.setVolume(50); } });
        denied("lights without lights", new Runnable() { public void run() { none.led(2, 2, 1, 1); } });
        denied("watch update without actions", new Runnable() { public void run() { none.watchUpdate(new JSONObject()); } });
        denied("go to without actions", new Runnable() { public void run() { none.goTo("kitchen"); } });
        denied("watch switch without settings", new Runnable() { public void run() { none.setWatch(true); } });
        denied("event without routines.run", new Runnable() { public void run() { none.routineEvent("doorbell"); } });
        denied("mqtt trigger without routines.run", new Runnable() { public void run() { none.triggerMessage("z/door", "x", false); } });
        check("no trigger topics without routines.run", none.triggerTopics().isEmpty());
        final PluginContext run = new PluginContext("p", h, new TreeSet<>(Arrays.asList("routines.run")), noStore());
        run.routineEvent("doorbell");
        run.triggerMessage("z/door", "x", false);
        eq("trigger topics with routines.run", Collections.singleton("z/door"), run.triggerTopics());
        eq("events and mqtt reach the robot", Arrays.asList("event doorbell from p", "mqtt z/door"), new ArrayList<>(h.calls));
        h.calls.clear();
        none.stop(); // always allowed
        none.action("stop");
        eq("stop needs no permission", Arrays.asList("stop", "stop"), new ArrayList<>(h.calls));

        h.calls.clear();
        final PluginContext all = new PluginContext("p", h, PluginPermissions.ALL.keySet(), noStore());
        all.action("dance");
        all.drive(40, 0);
        all.head(30);
        all.playSound(7);
        all.setVolume(50);
        all.runRoutine("greet");
        all.saveRoutine(new JSONObject().put("name", "x"));
        all.deleteRoutine("x");
        all.projector(0);
        all.led(2, 2, 1, 1);
        all.watchUpdate(new JSONObject().put("state", "playing"));
        all.setWatch(false);
        all.goTo("kitchen");
        eq("granted calls pass", Arrays.asList("action dance", "cmd move", "cmd head-angle", "sound 7", "volume 50",
                "routine greet", "save x", "delete x", "projector 0", "led", "watch playing", "watch-switch false", "go kitchen"), new ArrayList<>(h.calls));
        // Never available, whatever was granted.
        denied("pairing", new Runnable() { public void run() { all.action("pair"); } });
        denied("phone app switch", new Runnable() { public void run() { all.action("phone-app-on"); } });
        denied("unknown action", new Runnable() { public void run() { all.action("reboot"); } });
        for (final String c : new String[] {"shut-down", "reset-wdt", "debug", "d-leg-power", "ready", "gin", "led"}) {
            denied("controller " + c, new Runnable() {
                public void run() {
                    try {
                        all.controller(new JSONObject().put("cmd", c));
                    } catch (org.json.JSONException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
        }
        denied("bad led value", new Runnable() { public void run() { all.led(9, 0, 0, 0); } });
        denied("bad volume", new Runnable() { public void run() { all.setVolume(101); } });

        // Settings actions need "settings", not "actions".
        final PluginContext actions = new PluginContext("p", h, new TreeSet<>(Arrays.asList("actions")), noStore());
        denied("mute with only actions", new Runnable() { public void run() { actions.action("mute"); } });
        denied("voice-off with only actions", new Runnable() { public void run() { actions.action("voice-off"); } });
        denied("liveliness with only actions", new Runnable() { public void run() { actions.action("liveliness-full"); } });
        final PluginContext settings = new PluginContext("p", h, new TreeSet<>(Arrays.asList("settings")), noStore());
        settings.action("mute");
        denied("dance with only settings", new Runnable() { public void run() { settings.action("dance"); } });

        all.close();
        denied("calls after close", new Runnable() { public void run() { all.action("dance"); } });
        denied("stop after close", new Runnable() { public void run() { all.stop(); } });
        check("has() false after close", !all.has("status"));
    }

    static void lifecycle() throws Exception {
        File f = tempFile();
        FakeHost h = new FakeHost();
        TestPlugin on = new TestPlugin("on", true, "status"), off = new TestPlugin("off", false, "status");
        PluginManager m = new PluginManager(f, h, Arrays.<Plugin>asList(on, off));
        m.startAll();
        m.awaitIdle(2000);
        check("default-on plugin started", m.isRunning("on") && on.seen.contains("start"));
        check("default-off plugin not started", !m.isRunning("off") && off.seen.isEmpty());

        JSONObject r = ask(m, "enable", "off");
        check("enable ok", r.optBoolean("ok"));
        m.awaitIdle(2000);
        check("enabled plugin runs", m.isRunning("off") && off.seen.contains("start"));
        r = ask(m, "disable", "on");
        check("disable ok", r.optBoolean("ok"));
        check("disabled plugin stopped", !m.isRunning("on") && on.seen.contains("stop"));
        final PluginContext oldCtx = on.ctx;
        denied("stopped plugin's context", new Runnable() { public void run() { oldCtx.state(); } });
        contains("status shows disabled", m.status(), "on (disabled)");
        contains("status shows running", m.status(), "off (running, fine)");

        // Settings survive a restart of R2 Main.
        m.stopAll();
        TestPlugin on2 = new TestPlugin("on", true, "status"), off2 = new TestPlugin("off", false, "status");
        PluginManager m2 = new PluginManager(f, h, Arrays.<Plugin>asList(on2, off2));
        m2.startAll();
        m2.awaitIdle(2000);
        check("disabled stays disabled", !m2.isRunning("on"));
        check("enabled stays enabled", m2.isRunning("off"));
        m2.stopAll();

        eq("unknown plugin", "no plugin named nope", ask(m2, "enable", "nope").optString("error"));
        eq("unknown op", "unknown op x", ask(m2, "x", "on").optString("error"));
        JSONObject list = m2.request(new JSONObject().put("op", "list"));
        eq("list has both", 2, list.optJSONArray("plugins").length());
        check("list has permission names", list.optJSONObject("permissions").has("routines.edit"));

        try {
            new PluginManager(tempFile(), h, Arrays.<Plugin>asList(new TestPlugin("bad", true, "reboot")));
            check("unknown permission rejected", false);
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        try {
            new PluginManager(tempFile(), h, Arrays.<Plugin>asList(new TestPlugin("Bad Name", true)));
            check("bad name rejected", false);
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    static void grants() throws Exception {
        File f = tempFile();
        FakeHost h = new FakeHost();
        TestPlugin p = new TestPlugin("p", true, "status", "actions", "sound");
        PluginManager m = new PluginManager(f, h, Arrays.<Plugin>asList(p));
        m.startAll();
        m.awaitIdle(2000);
        eq("granted = asked by default", new TreeSet<>(Arrays.asList("actions", "sound", "status")), p.ctx.granted());

        JSONObject r = m.request(new JSONObject().put("op", "grant").put("name", "p").put("permissions", "status,sound"));
        check("narrowing ok: " + r, r.optBoolean("ok"));
        m.awaitIdle(2000);
        eq("restarted with fewer", new TreeSet<>(Arrays.asList("sound", "status")), p.ctx.granted());
        final PluginContext c = p.ctx;
        denied("revoked permission", new Runnable() { public void run() { c.action("dance"); } });

        r = m.request(new JSONObject().put("op", "grant").put("name", "p").put("permissions", "status,drive"));
        check("can't grant what it didn't ask for", !r.optBoolean("ok"));
        contains("says what it asks for", r.optString("error"), "doesn't ask for [drive]");
        r = m.request(new JSONObject().put("op", "grant").put("name", "p").put("permissions", "status,install"));
        contains("unknown permission name", r.optString("error"), "unknown permission \"install\"");
        m.stopAll();

        PluginManager m2 = new PluginManager(f, h, Arrays.<Plugin>asList(p));
        eq("grant kept", new TreeSet<>(Arrays.asList("sound", "status")), m2.granted("p"));
        // An empty grant: the plugin runs, but can do nothing except stop and log.
        m2.request(new JSONObject().put("op", "grant").put("name", "p").put("permissions", ""));
        eq("empty grant", Collections.<String>emptySet(), m2.granted("p"));
    }

    static void events() throws Exception {
        FakeHost h = new FakeHost();
        TestPlugin reader = new TestPlugin("reader", true, "status"), blind = new TestPlugin("blind", true, "actions");
        PluginManager m = new PluginManager(tempFile(), h, Arrays.<Plugin>asList(reader, blind));
        m.startAll();
        m.awaitIdle(2000);
        m.event(Plugin.EVENT_BUTTON, new JSONObject().put("value", 4));
        m.event(Plugin.EVENT_RUNNING, new JSONObject().put("name", "dance"));
        m.event(Plugin.EVENT_STATE, null);
        m.awaitIdle(2000);
        check("status plugin gets events: " + reader.seen, reader.seen.containsAll(Arrays.asList("button", "running", "state")));
        eq("plugin without status gets none", Arrays.asList("start"), new ArrayList<>(blind.seen));
        m.stopAll();
        int before = reader.seen.size();
        m.event(Plugin.EVENT_STATE, null);
        Thread.sleep(50);
        eq("no events after stop", before, reader.seen.size());
    }

    static void failures() throws Exception {
        FakeHost h = new FakeHost();
        TestPlugin bad = new TestPlugin("bad", true, "status");
        PluginManager m = new PluginManager(tempFile(), h, Arrays.<Plugin>asList(bad));
        m.startAll();
        m.awaitIdle(2000);
        bad.throwOnEvent = true;
        for (int i = 0; i < PluginManager.MAX_FAILURES - 1; i++) m.event(Plugin.EVENT_BUTTON, new JSONObject());
        m.awaitIdle(2000);
        check("still running after a few failures", m.isRunning("bad"));
        m.event(Plugin.EVENT_BUTTON, new JSONObject());
        for (int i = 0; i < 100 && m.isRunning("bad"); i++) Thread.sleep(10);
        check("stopped after MAX_FAILURES", !m.isRunning("bad"));
        contains("status says failed", m.status(), "failed");
        check("still enabled (starts again next time)", m.enabled("bad"));

        TestPlugin noStart = new TestPlugin("nostart", true, "status");
        noStart.throwOnStart = true;
        PluginManager m2 = new PluginManager(tempFile(), h, Arrays.<Plugin>asList(noStart));
        m2.startAll();
        for (int i = 0; i < 100 && m2.isRunning("nostart"); i++) Thread.sleep(10);
        check("failed start stops the plugin", !m2.isRunning("nostart"));
        contains("failed start in status", m2.status(), "start failed on purpose");
    }

    static void config() throws Exception {
        File f = tempFile();
        FakeHost h = new FakeHost();
        TestPlugin p = new TestPlugin("p", true, "status");
        PluginManager m = new PluginManager(f, h, Arrays.<Plugin>asList(p));
        m.startAll();
        m.awaitIdle(2000);
        JSONObject r = m.request(new JSONObject().put("op", "config").put("name", "p")
                .put("config", new JSONObject().put("below", 30).put("api_password", "hunter2").put("x", 1)));
        check("config ok", r.optBoolean("ok"));
        m.awaitIdle(2000);
        check("plugin told", p.seen.contains("configure") && p.config.optInt("below") == 30);
        eq("secrets hidden", "(set)", r.optJSONObject("plugin").optJSONObject("config").optString("api_password"));
        r = m.request(new JSONObject().put("op", "config").put("name", "p").put("config", new JSONObject().put("x", JSONObject.NULL)));
        check("null removes a key", !m.config("p").has("x") && m.config("p").optInt("below") == 30);
        eq("secret kept by merge", "hunter2", m.config("p").optString("api_password"));
        p.ctx.saveConfig(new JSONObject().put("below", 10));
        m.stopAll();
        TestPlugin p2 = new TestPlugin("p", true, "status");
        PluginManager m2 = new PluginManager(f, h, Arrays.<Plugin>asList(p2));
        m2.startAll();
        m2.awaitIdle(2000);
        eq("plugin's own saved config back at start", 10, p2.config.optInt("below"));
        m2.stopAll();
        eq("config must be an object", "send the config as a JSON object",
                m2.request(new JSONObject().put("op", "config").put("name", "p")).optString("error"));
    }

    static void batteryAlert() throws Exception {
        FakeHost h = new FakeHost();
        BatteryAlertPlugin b = new BatteryAlertPlugin();
        PluginManager m = new PluginManager(tempFile(), h, Arrays.<Plugin>asList(b));
        m.startAll();
        m.awaitIdle(2000);
        check("example is off by default", !m.isRunning(BatteryAlertPlugin.NAME));
        ask(m, "enable", BatteryAlertPlugin.NAME);
        m.awaitIdle(2000);
        h.battery = 20;
        m.event(Plugin.EVENT_STATE, null);
        m.awaitIdle(2000);
        h.battery = 18;
        m.event(Plugin.EVENT_STATE, null);
        m.awaitIdle(2000);
        eq("one alert below 25%", Arrays.asList("sound 3"), new ArrayList<>(h.calls));
        h.charging = 1;
        m.event(Plugin.EVENT_STATE, null);
        m.awaitIdle(2000);
        h.charging = 0;
        m.event(Plugin.EVENT_STATE, null);
        m.awaitIdle(2000);
        eq("alerts again after charging", Arrays.asList("sound 3", "sound 3"), new ArrayList<>(h.calls));
        // Without the sound permission it can't play anything.
        h.calls.clear();
        m.request(new JSONObject().put("op", "grant").put("name", BatteryAlertPlugin.NAME).put("permissions", "status"));
        m.awaitIdle(2000);
        eq("no sound without permission", 0, h.calls.size());
        m.stopAll();
    }

    static void homeAssistant() throws Exception {
        FakeHost h = new FakeHost();
        final List<String> mqtt = new CopyOnWriteArrayList<>();
        LocalNet.Interfaces noNet = new LocalNet.Interfaces() {
            public List<LocalNet.Subnet> list() {
                return Collections.emptyList();
            }
        };
        HomeAssistantPlugin ha = new HomeAssistantPlugin(new HomeAssistantPlugin.StatusSink() {
            public void mqttStatus(String state) {
                mqtt.add(state);
            }
        }, noNet);
        PluginManager m = new PluginManager(tempFile(), h, Arrays.<Plugin>asList(ha));
        eq("HA asks for", new TreeSet<>(Arrays.asList("actions", "routines.run", "settings", "status")), ha.permissions());
        check("HA never asks for drive or edit", !ha.permissions().contains("drive") && !ha.permissions().contains("routines.edit"));
        m.startAll();
        m.awaitIdle(2000);
        check("HA on by default", m.isRunning(HomeAssistantPlugin.NAME));
        ask(m, "disable", HomeAssistantPlugin.NAME);
        contains("disabled HA says so", ha.status(), "plugin is disabled");
        m.request(new JSONObject().put("op", "grant").put("name", HomeAssistantPlugin.NAME).put("permissions", "actions"));
        ask(m, "enable", HomeAssistantPlugin.NAME);
        for (int i = 0; i < 100 && m.isRunning(HomeAssistantPlugin.NAME); i++) Thread.sleep(10);
        check("HA without status doesn't start", !m.isRunning(HomeAssistantPlugin.NAME));
        contains("and says why", m.status(), "needs the \"status\" permission");
        m.stopAll();
    }
}
