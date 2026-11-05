package dev.r2hack.main;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home Assistant through MQTT discovery: R2 appears as a device with battery, charging, mode and
 * current-action sensors, buttons for the actions, switches for the lightsaber, arm, projectors,
 * displays and settings, and a volume slider. Commands from Home Assistant run as ordinary actions,
 * so they go through the agent's motion guard (watchdog, low battery) like everything else.
 *
 * The broker is set with POST /mqtt on the agent, which passes the settings here. Like API clients,
 * the broker must be on the robot's own subnet or in an allowed extra subnet (POST /clients/allow).
 * Only the actions in BUTTONS, the switches below and the activity select can be run from MQTT: no pairing,
 * no phone-app switch, nothing that installs or reboots.
 *
 * No Android classes, so it can be tested on a plain JVM against a real broker.
 */
final class HomeAssistant {
    interface Host {
        /**
         * The robot's state: battery (int, -1 unknown), charging (0/1/2), lightsaber, arm, lcd_s, lcd_l
         * (booleans), projector (0/1/2), mode (string), action (string), error (string), muted, voice,
         * face (booleans), volume (0-100), liveliness (one of LIVELINESS, the activity preset), name, uuid,
         * version (strings), watch (WatchWithMe.status()).
         */
        JSONObject haState();

        void action(String name);

        /** Stored routine names (a-z, 0-9 and -), each published as a button. */
        List<String> routineNames();

        void runRoutine(String name);

        void projector(int mode);

        void setVolume(int percent);

        /** A media player update for watch-with-me (docs/watch-with-me.md), from the watch/set topic. */
        void watchUpdate(JSONObject msg);

        /** The "Watch with me" switch. */
        void setWatch(boolean on);

        void log(String msg);

        /** The connection state changed (for the agent's /status). */
        void haStatus(String state);
    }

    /** Buttons: action name -> display name. */
    static final String[][] BUTTONS = {
            {"stop", "Stop"}, {"wake", "Wake up"}, {"turn-left", "Turn left"}, {"turn-right", "Turn right"},
            {"turn-around", "Turn around"}, {"forward", "Go forward"}, {"circle", "Walk a circle"},
            {"patrol", "Patrol"}, {"dance", "Dance"}, {"shake-head", "Shake head"},
            {"who-are-you", "Who are you"}, {"noise", "Make some noise"},
            {"flash-front", "Flash front light"}, {"flash-back", "Flash back light"},
    };

    /** Switches: id -> display name, icon. */
    static final String[][] SWITCHES = {
            {"lightsaber", "Lightsaber", "mdi:sword"}, {"arm", "Arm", "mdi:robot-industrial"},
            {"projector-leia", "Projector: Princess Leia", "mdi:projector"},
            {"projector-luke", "Projector: Skywalker", "mdi:projector"},
            {"short-lcd", "Short display", "mdi:led-strip"}, {"long-lcd", "Long display", "mdi:led-strip"},
            {"mute", "Mute", "mdi:volume-off"}, {"voice", "Voice commands", "mdi:microphone"},
            {"face", "Face tracking", "mdi:face-recognition"},
            {"watch", "Watch with me", "mdi:movie-open-play"},
    };

    static final String[] MODES = {"ready", "sleeping", "pairing", "patrol", "phone control"};

    /**
     * Activity presets (the "liveliness" setting), published as one select. Choosing one runs the
     * action "liveliness-<level>", the same as the web page and POST /action.
     */
    static final String[] LIVELINESS = {"off", "quiet", "chatty", "full"};

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int KEEPALIVE_S = 60;
    private static final long REFUSED_RETRY_MS = 30_000;
    private static final long MAX_BACKOFF_MS = 60_000;

    static final class Settings {
        boolean enabled;
        String host = "";
        int port = 1883;
        String username = "", password = "";
        String prefix = "homeassistant";
        List<LocalNet.Subnet> extraSubnets = Collections.emptyList();

        static Settings from(JSONObject m) {
            Settings s = new Settings();
            s.host = m.optString("host", "").trim();
            s.enabled = m.optBoolean("enabled", true) && !s.host.isEmpty();
            s.port = m.optInt("port", 1883);
            s.username = m.optString("username", "");
            s.password = m.optString("password", "");
            String p = m.optString("prefix", "").trim();
            if (!p.isEmpty()) s.prefix = p;
            List<LocalNet.Subnet> extra = new ArrayList<>();
            JSONArray a = m.optJSONArray("extra_subnets");
            for (int i = 0; a != null && i < a.length(); i++) {
                try {
                    extra.add(LocalNet.Subnet.parse(a.optString(i)));
                } catch (Exception ignored) {
                }
            }
            s.extraSubnets = extra;
            return s;
        }

        String key() {
            return enabled + "|" + host + "|" + port + "|" + username + "|" + password + "|" + prefix;
        }
    }

    private final Host host;
    private final LocalNet.Interfaces ifaces;
    private final Object lock = new Object();
    private volatile Settings settings = new Settings();
    private volatile MqttClient client;
    private volatile String status = "off (set a broker with POST /mqtt)";
    private int settingsVersion;

    // Per connection: what was published, so only changes go out.
    private final Map<String, String> published = new HashMap<>();
    private String discoveredFor = "";
    private final java.util.Set<String> discoveredTopics = new java.util.HashSet<>();
    private volatile String connectedNode = "";
    private volatile boolean republish;

    HomeAssistant(Host host) {
        this(host, LocalNet.SYSTEM);
    }

    HomeAssistant(Host host, LocalNet.Interfaces ifaces) {
        this.host = host;
        this.ifaces = ifaces;
    }

    void start() {
        Thread c = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "ha-mqtt");
        c.setDaemon(true);
        c.start();
        Thread p = new Thread(new Runnable() {
            @Override
            public void run() {
                publishLoop();
            }
        }, "ha-publish");
        p.setDaemon(true);
        p.start();
    }

    String status() {
        return "home assistant: " + status;
    }

    /** New settings from the agent ({"agent":"mqtt",...}); reconnects if they changed. */
    void configure(JSONObject msg) {
        Settings s = Settings.from(msg);
        Settings old = settings;
        settings = s;
        if (s.key().equals(old.key()) && sameSubnets(s, old)) return;
        synchronized (lock) {
            settingsVersion++;
            lock.notifyAll();
        }
        MqttClient c = client;
        if (c != null) {
            try {
                c.publish(availabilityTopic(), "offline", true);
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
    }

    /** Wakes the publisher, e.g. when the robot's state changed. */
    void stateChanged() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private static boolean sameSubnets(Settings a, Settings b) {
        if (a.extraSubnets.size() != b.extraSubnets.size()) return false;
        for (int i = 0; i < a.extraSubnets.size(); i++) {
            LocalNet.Subnet x = a.extraSubnets.get(i), y = b.extraSubnets.get(i);
            if (x.prefix != y.prefix || !java.util.Arrays.equals(x.net, y.net)) return false;
        }
        return true;
    }

    private void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        host.log("home assistant: " + s);
        host.haStatus(s);
    }

    // ---- connection ----------------------------------------------------------------------

    private void connectLoop() {
        long backoff = 5_000;
        while (true) {
            int version;
            synchronized (lock) {
                version = settingsVersion;
            }
            Settings s = settings;
            if (!s.enabled) {
                setStatus("off (set a broker with POST /mqtt)");
                waitForChange(version, 0);
                continue;
            }
            InetAddress addr;
            try {
                addr = pickBroker(s);
            } catch (Exception e) {
                setStatus("can't find broker " + s.host + ": " + e.getMessage());
                waitForChange(version, REFUSED_RETRY_MS);
                continue;
            }
            if (addr == null) {
                setStatus("refused broker " + s.host + ": not on the robot's own subnet"
                        + " (allow it with POST /clients/allow, tools\\r2.ps1 allow-subnets)");
                waitForChange(version, REFUSED_RETRY_MS);
                continue;
            }
            MqttClient c = new MqttClient(KEEPALIVE_S);
            long connectedAt = 0;
            try {
                String node = nodeId();
                connectedNode = node;
                c.connect(addr, s.port, "r2d2-" + node, s.username, s.password,
                        new MqttClient.Will(availabilityTopic(), "offline", true));
                connectedAt = System.currentTimeMillis();
                synchronized (published) {
                    published.clear();
                    discoveredFor = "";
                }
                c.subscribe(base() + "/action/run", base() + "/routine/run", base() + "/switch/+/set", base() + "/volume/set",
                        base() + "/liveliness/set", watchTopic(), s.prefix + "/status");
                client = c;
                setStatus("connected to " + addr.getHostAddress() + ":" + s.port);
                stateChanged(); // publish discovery and state now
                c.readLoop(new MqttClient.Callback() {
                    @Override
                    public void onMessage(String topic, byte[] payload) {
                        onCommand(topic, new String(payload, UTF8).trim());
                    }
                });
            } catch (Exception e) {
                if (version == currentVersion()) setStatus("not connected to " + s.host + ": " + e.getMessage());
            } finally {
                client = null;
                c.close();
            }
            if (connectedAt > 0 && System.currentTimeMillis() - connectedAt > MAX_BACKOFF_MS) backoff = 5_000;
            waitForChange(version, backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private int currentVersion() {
        synchronized (lock) {
            return settingsVersion;
        }
    }

    /** Waits until the settings change, or ms (0: no limit). */
    private void waitForChange(int version, long ms) {
        long until = System.currentTimeMillis() + ms;
        synchronized (lock) {
            while (settingsVersion == version) {
                long left = ms == 0 ? 0 : until - System.currentTimeMillis();
                if (ms != 0 && left <= 0) return;
                try {
                    lock.wait(left);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** The first address of the broker that passes the same-subnet rule, or null. */
    private InetAddress pickBroker(Settings s) throws Exception {
        for (InetAddress a : InetAddress.getAllByName(s.host)) {
            if (LocalNet.allowed(a, s.extraSubnets, ifaces)) return a;
        }
        return null;
    }

    // ---- commands from Home Assistant -------------------------------------------------------

    void onCommand(String topic, String payload) {
        String b = base();
        if (topic.equals(settings.prefix + "/status")) {
            if ("online".equals(payload)) { // Home Assistant restarted: send everything again
                republish = true;
                stateChanged();
            }
            return;
        }
        if (topic.equals(b + "/action/run")) {
            for (String[] btn : BUTTONS) {
                if (btn[0].equals(payload)) {
                    host.log("home assistant: action " + payload);
                    host.action(payload);
                    return;
                }
            }
            host.log("home assistant: ignored unknown action " + clip(payload));
            return;
        }
        if (topic.equals(b + "/routine/run")) {
            if (host.routineNames().contains(payload)) {
                host.log("home assistant: routine " + payload);
                host.runRoutine(payload);
            } else {
                host.log("home assistant: ignored unknown routine " + clip(payload));
            }
            return;
        }
        if (topic.equals(b + "/volume/set")) {
            try {
                int v = (int) Math.round(Double.parseDouble(payload));
                if (v >= 0 && v <= 100) host.setVolume(v);
            } catch (NumberFormatException ignored) {
            }
            stateChanged();
            return;
        }
        if (topic.equals(b + "/liveliness/set")) {
            String level = payload.toLowerCase(java.util.Locale.US);
            if (java.util.Arrays.asList(LIVELINESS).contains(level)) {
                if (!level.equals(host.haState().optString("liveliness"))) {
                    host.log("home assistant: activity " + level);
                    host.action("liveliness-" + level);
                }
            } else {
                host.log("home assistant: ignored unknown activity " + clip(payload));
            }
            stateChanged();
            return;
        }
        if (topic.equals(watchTopic())) {
            try {
                host.watchUpdate(new JSONObject(payload));
            } catch (Exception e) {
                host.log("home assistant: watch update is not a JSON object: " + clip(payload));
            }
            return;
        }
        String sw = b + "/switch/";
        if (topic.startsWith(sw) && topic.endsWith("/set")) {
            String id = topic.substring(sw.length(), topic.length() - 4);
            boolean on;
            if ("ON".equalsIgnoreCase(payload)) on = true;
            else if ("OFF".equalsIgnoreCase(payload)) on = false;
            else return;
            setSwitch(id, on);
            stateChanged();
        }
    }

    /** Runs the action that brings the switch to the wanted state, if it isn't there already. */
    private void setSwitch(String id, boolean on) {
        Boolean now = switchState(id, host.haState());
        if (now == null || now == on) return;
        host.log("home assistant: " + id + (on ? " on" : " off"));
        switch (id) {
            case "lightsaber":
            case "arm":
            case "short-lcd":
            case "long-lcd":
                host.action(id); // toggles
                break;
            case "projector-leia":
                if (on) host.action("leia");
                else host.projector(0);
                break;
            case "projector-luke":
                if (on) host.action("luke");
                else host.projector(0);
                break;
            case "mute":
                host.action(on ? "mute" : "unmute");
                break;
            case "voice":
                host.action(on ? "voice-on" : "voice-off");
                break;
            case "face":
                host.action(on ? "face-on" : "face-off");
                break;
            case "watch":
                host.setWatch(on);
                break;
            default:
                break;
        }
    }

    /** A switch's state from haState(); null for an unknown switch. */
    static Boolean switchState(String id, JSONObject st) {
        switch (id) {
            case "lightsaber": return st.optBoolean("lightsaber");
            case "arm": return st.optBoolean("arm");
            case "projector-leia": return st.optInt("projector") == 1;
            case "projector-luke": return st.optInt("projector") == 2;
            case "short-lcd": return st.optBoolean("lcd_s");
            case "long-lcd": return st.optBoolean("lcd_l");
            case "mute": return st.optBoolean("muted");
            case "voice": return st.optBoolean("voice");
            case "face": return st.optBoolean("face");
            case "watch": {
                JSONObject w = st.optJSONObject("watch");
                return w != null && w.optBoolean("enabled");
            }
            default: return null;
        }
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    // ---- publishing ---------------------------------------------------------------------------

    private void publishLoop() {
        while (true) {
            synchronized (lock) {
                try {
                    lock.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
            MqttClient c = client;
            if (c == null) continue;
            try {
                publishAll(c);
                c.keepAlive();
            } catch (Exception e) {
                host.log("home assistant: publish failed: " + e.getMessage());
                c.close(); // readLoop ends and the connection loop reconnects
            }
        }
    }

    /** Sends discovery (when new or the name changed) and every state that changed since the last call. */
    void publishAll(MqttClient c) throws Exception {
        JSONObject st = host.haState();
        // The robot's ID changed (identity imported): reconnect with the new topics and will.
        if (!nodeId().equals(connectedNode)) throw new Exception("robot ID changed, reconnecting");
        boolean again = republish;
        republish = false;
        List<String> routineList = new ArrayList<>(host.routineNames());
        Collections.sort(routineList);
        String discoveryKey = st.optString("name") + "|" + st.optString("version") + "|" + settings.prefix + "|" + routineList;
        synchronized (published) {
            if (again) published.clear();
            if (again || !discoveryKey.equals(discoveredFor)) {
                Map<String, JSONObject> d = discovery(st, routineList);
                for (Map.Entry<String, JSONObject> e : d.entrySet()) {
                    c.publish(e.getKey(), e.getValue().toString(), true);
                }
                // A deleted routine: an empty retained config removes its button from Home Assistant.
                for (String old : discoveredTopics) if (!d.containsKey(old)) c.publish(old, "", true);
                discoveredTopics.clear();
                discoveredTopics.addAll(d.keySet());
                c.publish(availabilityTopic(), "online", true);
                discoveredFor = discoveryKey;
            }
            for (Map.Entry<String, String> e : states(st).entrySet()) {
                if (e.getValue().equals(published.get(e.getKey()))) continue;
                c.publish(e.getKey(), e.getValue(), true);
                published.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Topic -> payload for every state. Battery is left out while unknown. */
    Map<String, String> states(JSONObject st) {
        String b = base();
        Map<String, String> m = new LinkedHashMap<>();
        int batt = st.optInt("battery", -1);
        if (batt >= 0) m.put(b + "/battery", String.valueOf(batt));
        m.put(b + "/charging", st.optInt("charging") == 1 ? "ON" : "OFF");
        m.put(b + "/mode", st.optString("mode", "ready"));
        m.put(b + "/action", st.optString("action", "idle"));
        String err = st.optString("error", "");
        m.put(b + "/error", err.isEmpty() ? "none" : err);
        m.put(b + "/volume", String.valueOf(st.optInt("volume")));
        String live = st.optString("liveliness", "");
        if (!live.isEmpty()) m.put(b + "/liveliness", live); // left out until R2 Main reports it
        for (String[] sw : SWITCHES) m.put(b + "/switch/" + sw[0], switchState(sw[0], st) ? "ON" : "OFF");
        JSONObject w = st.optJSONObject("watch");
        if (w != null) {
            m.put(b + "/watch/state", w.optString("state", "off"));
            // No position here: it would change every second. Home Assistant has the player's own position.
            JSONObject attrs = new JSONObject();
            try {
                for (String k : new String[] {"cue", "title", "last", "skipped"}) attrs.put(k, w.opt(k));
                JSONObject next = w.optJSONObject("next");
                attrs.put("next", next == null ? "" : next.optString("at") + " " + next.optString("what")
                        + (next.optString("note").isEmpty() ? "" : " (" + next.optString("note") + ")"));
            } catch (Exception ignored) {
            }
            m.put(b + "/watch/attributes", attrs.toString());
        }
        return m;
    }

    /** Discovery topic -> config, one per entity (Home Assistant's per-component discovery). */
    Map<String, JSONObject> discovery(JSONObject st, List<String> routineList) throws Exception {
        String b = base(), node = nodeId(), p = settings.prefix;
        JSONObject device = new JSONObject()
                .put("identifiers", new JSONArray().put("r2d2_" + node))
                .put("name", st.optString("name", "R2-D2"))
                .put("manufacturer", "Fanhome R2-D2, custom software")
                .put("model", "R2-D2")
                .put("sw_version", st.optString("version"));
        JSONObject origin = new JSONObject().put("name", "R2 Main")
                .put("url", "https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software");
        Map<String, JSONObject> m = new LinkedHashMap<>();

        m.put(p + "/sensor/" + node + "/battery/config", entity("Battery", node, "battery", device, origin)
                .put("state_topic", b + "/battery").put("device_class", "battery")
                .put("unit_of_measurement", "%").put("state_class", "measurement"));
        m.put(p + "/binary_sensor/" + node + "/charging/config", entity("Charging", node, "charging", device, origin)
                .put("state_topic", b + "/charging").put("device_class", "battery_charging"));
        JSONArray modes = new JSONArray();
        for (String mode : MODES) modes.put(mode);
        m.put(p + "/sensor/" + node + "/mode/config", entity("Mode", node, "mode", device, origin)
                .put("state_topic", b + "/mode").put("device_class", "enum").put("options", modes)
                .put("icon", "mdi:robot"));
        m.put(p + "/sensor/" + node + "/action/config", entity("Action", node, "action", device, origin)
                .put("state_topic", b + "/action").put("icon", "mdi:play-circle-outline"));
        m.put(p + "/sensor/" + node + "/error/config", entity("Controller error", node, "error", device, origin)
                .put("state_topic", b + "/error").put("entity_category", "diagnostic")
                .put("icon", "mdi:alert-circle-outline"));
        m.put(p + "/number/" + node + "/volume/config", entity("Volume", node, "volume", device, origin)
                .put("state_topic", b + "/volume").put("command_topic", b + "/volume/set")
                .put("min", 0).put("max", 100).put("step", 5).put("unit_of_measurement", "%")
                .put("icon", "mdi:volume-high"));
        JSONArray levels = new JSONArray();
        for (String l : LIVELINESS) levels.put(l);
        m.put(p + "/select/" + node + "/liveliness/config", entity("Activity", node, "liveliness", device, origin)
                .put("state_topic", b + "/liveliness").put("command_topic", b + "/liveliness/set")
                .put("options", levels).put("icon", "mdi:emoticon-happy-outline"));
        m.put(p + "/sensor/" + node + "/watch_state/config", entity("Watch-with-me state", node, "watch_state", device, origin)
                .put("state_topic", b + "/watch/state").put("json_attributes_topic", b + "/watch/attributes")
                .put("icon", "mdi:movie-open-play"));
        for (String[] btn : BUTTONS) {
            String obj = "action_" + btn[0].replace('-', '_');
            JSONObject e = entity(btn[1], node, obj, device, origin)
                    .put("command_topic", b + "/action/run").put("payload_press", btn[0]);
            if ("stop".equals(btn[0])) e.put("icon", "mdi:stop-circle");
            m.put(p + "/button/" + node + "/" + obj + "/config", e);
        }
        for (String r : routineList) {
            String obj = "routine_" + r.replace('-', '_');
            m.put(p + "/button/" + node + "/" + obj + "/config", entity("Routine " + r.replace('-', ' '), node, obj, device, origin)
                    .put("command_topic", b + "/routine/run").put("payload_press", r).put("icon", "mdi:script-text-play"));
        }
        for (String[] sw : SWITCHES) {
            String obj = sw[0].replace('-', '_');
            m.put(p + "/switch/" + node + "/" + obj + "/config", entity(sw[1], node, obj, device, origin)
                    .put("state_topic", b + "/switch/" + sw[0]).put("command_topic", b + "/switch/" + sw[0] + "/set")
                    .put("icon", sw[2]));
        }
        return m;
    }

    private JSONObject entity(String name, String node, String obj, JSONObject device, JSONObject origin)
            throws Exception {
        return new JSONObject().put("name", name).put("unique_id", "r2d2_" + node + "_" + obj)
                .put("availability_topic", availabilityTopic()).put("device", device).put("origin", origin);
    }

    /** The robot's ID, safe as a topic level and in entity IDs. */
    String nodeId() {
        String id = host.haState().optString("uuid", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.US);
        if (id.length() > 12) id = id.substring(0, 12);
        return id.isEmpty() ? "r2d2" : id;
    }

    String base() {
        return "r2d2/" + nodeId();
    }

    /** Where Home Assistant sends media player updates for watch-with-me. */
    String watchTopic() {
        return base() + "/watch/set";
    }

    String availabilityTopic() {
        return base() + "/availability";
    }
}
