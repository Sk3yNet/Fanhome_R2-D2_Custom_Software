package dev.r2hack.main;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * A small example plugin (off by default): when the battery falls below a threshold while R2 isn't
 * charging, it plays a sound once, and again only after the battery has been back above the threshold.
 * Config: {"below": 25, "sound": 3}. It asks only for "status" and "sound". See docs/plugins.md.
 */
final class BatteryAlertPlugin implements Plugin {
    static final String NAME = "battery-alert";
    static final int DEFAULT_BELOW = 25, DEFAULT_SOUND = 3; // sound 3: the factory "pairing failed" sound

    private volatile PluginContext ctx;
    private volatile int below = DEFAULT_BELOW, sound = DEFAULT_SOUND;
    private boolean alerted;
    private volatile String status = "";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Example: plays a sound when the battery falls below a threshold (config: below, sound)";
    }

    @Override
    public Set<String> permissions() {
        return new TreeSet<>(Arrays.asList(PluginPermissions.STATUS, PluginPermissions.SOUND));
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public void start(PluginContext c, JSONObject config) throws Exception {
        ctx = c;
        configure(config);
        onEvent(EVENT_STATE, c.state());
    }

    @Override
    public void configure(JSONObject config) throws Exception {
        int b = config.optInt("below", DEFAULT_BELOW), s = config.optInt("sound", DEFAULT_SOUND);
        if (b < 1 || b > 99) throw new Exception("below must be 1-99");
        if (s < 0 || s > 200) throw new Exception("sound must be 0-200");
        below = b;
        sound = s;
        status = "alerts below " + below + "%";
    }

    @Override
    public void onEvent(String type, JSONObject data) {
        if (!EVENT_STATE.equals(type)) return;
        int battery = data.optInt("battery", -1);
        boolean charging = data.optInt("charging", 0) != 0;
        if (battery < 0) return;
        if (battery >= below || charging) {
            alerted = false;
        } else if (!alerted) {
            alerted = true;
            ctx.log("battery " + battery + "%, below " + below + "%");
            ctx.playSound(sound);
        }
    }

    @Override
    public void stop() {
        ctx = null;
    }

    @Override
    public String status() {
        return status;
    }
}
