package dev.r2hack.main;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * Home Assistant over MQTT discovery (HomeAssistant.java) as a plugin: everything it does to the robot
 * goes through its PluginContext. The broker settings still come from the agent (POST /mqtt); this
 * plugin only decides whether they are used. Taking a permission away removes what needs it: without
 * "routines.run" there are no routine buttons, and buttons or switches that need a missing permission
 * are ignored (and logged). Without "status" it doesn't connect at all.
 */
final class HomeAssistantPlugin implements Plugin {
    static final String NAME = "home-assistant";

    /** Where the connection state goes (the agent's GET /mqtt). */
    interface StatusSink {
        void mqttStatus(String state);
    }

    private final StatusSink sink;
    private final HomeAssistant ha;
    private volatile PluginContext ctx;
    private volatile JSONObject mqtt = new JSONObject();
    private boolean threadsStarted;

    HomeAssistantPlugin(StatusSink sink) {
        this(sink, LocalNet.SYSTEM);
    }

    HomeAssistantPlugin(StatusSink sink, LocalNet.Interfaces ifaces) {
        this.sink = sink;
        this.ha = new HomeAssistant(host, ifaces);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Home Assistant through MQTT discovery: sensors, action and routine buttons, switches, volume (broker: POST /mqtt)";
    }

    @Override
    public Set<String> permissions() {
        return new TreeSet<>(Arrays.asList(PluginPermissions.STATUS, PluginPermissions.ACTIONS,
                PluginPermissions.ROUTINES_RUN, PluginPermissions.SETTINGS));
    }

    @Override
    public boolean enabledByDefault() {
        return true; // it only connects once a broker is set with POST /mqtt
    }

    /** Broker settings from the agent ({"agent":"mqtt",...}), whether or not the plugin runs. */
    void mqttSettings(JSONObject msg) {
        mqtt = msg;
        if (ctx != null) ha.configure(msg);
    }

    @Override
    public synchronized void start(PluginContext c, JSONObject config) throws Exception {
        if (!c.has(PluginPermissions.STATUS)) throw new Exception("needs the \"status\" permission to show R2 in Home Assistant");
        ctx = c;
        if (!threadsStarted) {
            ha.start();
            threadsStarted = true;
        }
        ha.configure(mqtt);
    }

    @Override
    public void configure(JSONObject config) {
        // No plugin config: the broker is set with POST /mqtt.
    }

    @Override
    public void onEvent(String type, JSONObject data) {
        ha.stateChanged(); // state, running action and routine list are all published from the current state
    }

    @Override
    public synchronized void stop() {
        ctx = null;
        try {
            ha.configure(new JSONObject().put("host", ""), "off (the home-assistant plugin is disabled; POST /plugin?name=home-assistant&enabled=1)");
        } catch (Exception ignored) {
        }
    }

    /** The MQTT topic Home Assistant sends watch-with-me updates to. */
    String watchTopic() {
        return ha.watchTopic();
    }

    @Override
    public String status() {
        return ha.status().replaceFirst("^home assistant: ", "");
    }

    // HomeAssistant's calls, through the context. A refused call is logged and skipped.
    private final HomeAssistant.Host host = new HomeAssistant.Host() {
        @Override
        public JSONObject haState() {
            PluginContext c = ctx;
            try {
                if (c != null) return c.state();
            } catch (PluginContext.Denied d) {
                log(d.getMessage());
            }
            return new JSONObject();
        }

        @Override
        public void action(String name) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.action(name);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public List<String> routineNames() {
            PluginContext c = ctx;
            if (c == null || !c.has(PluginPermissions.ROUTINES_RUN)) return Collections.emptyList();
            return c.routineNames();
        }

        @Override
        public void runRoutine(String name) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.runRoutine(name);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public List<String> placeNames() {
            PluginContext c = ctx;
            if (c == null || !c.has(PluginPermissions.ACTIONS)) return Collections.emptyList();
            return c.placeNames();
        }

        @Override
        public void goTo(String place) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.goTo(place);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public void projector(int mode) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.projector(mode);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public void setVolume(int percent) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.setVolume(percent);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public void watchUpdate(JSONObject msg) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.log("watch " + c.watchUpdate(msg));
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            } catch (IllegalArgumentException e) {
                c.log("watch update ignored: " + e.getMessage());
            }
        }

        @Override
        public void setWatch(boolean on) {
            PluginContext c = ctx;
            if (c == null) return;
            try {
                c.setWatch(on);
            } catch (PluginContext.Denied d) {
                c.log(d.getMessage());
            }
        }

        @Override
        public void log(String msg) {
            PluginContext c = ctx;
            if (c != null) c.log(msg);
        }

        @Override
        public void haStatus(String state) {
            sink.mqttStatus(state);
        }
    };
}
