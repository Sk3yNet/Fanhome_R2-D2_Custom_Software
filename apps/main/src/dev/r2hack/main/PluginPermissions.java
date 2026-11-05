package dev.r2hack.main;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a plugin may do. A plugin asks for some of these (Plugin.permissions()), and the owner can take
 * any of them away again (POST /plugin?name=...&grant=...). PluginContext checks them on every call.
 *
 * There is deliberately no permission for installing software, rebooting or shutting down, adb, Wi-Fi,
 * pairing, the phone app switch, API tokens or the controller's power-off command: R2 Main has no call
 * for them in PluginContext, so no grant can give them to a plugin. "Stop" needs no permission: any
 * running plugin may stop the robot.
 */
final class PluginPermissions {
    static final String STATUS = "status";               // read the robot's state, get events
    static final String ACTIONS = "actions";             // run built-in actions (dance, lightsaber, projector...), watch-with-me updates
    static final String DRIVE = "drive";                 // drive, move the head, send movement commands
    static final String ROUTINES_RUN = "routines.run";   // list and run stored routines
    static final String ROUTINES_EDIT = "routines.edit"; // save and delete routines
    static final String SOUND = "sound";                 // play droid sounds
    static final String LIGHTS = "lights";               // set the status lights
    static final String SETTINGS = "settings";           // volume, mute, voice commands, face tracking, watch-with-me switch

    /** Every permission, with the line GET /plugins shows for it. */
    static final Map<String, String> ALL;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(STATUS, "read the robot's state and events");
        m.put(ACTIONS, "run built-in actions, and react along with a film (watch-with-me)");
        m.put(DRIVE, "drive and move the head");
        m.put(ROUTINES_RUN, "list and run routines");
        m.put(ROUTINES_EDIT, "save and delete routines");
        m.put(SOUND, "play droid sounds");
        m.put(LIGHTS, "set the status lights");
        m.put(SETTINGS, "change volume, mute, voice, face tracking and watch-with-me");
        ALL = Collections.unmodifiableMap(m);
    }

    /** Actions that change a setting, so they need SETTINGS instead of ACTIONS. */
    static final Set<String> SETTING_ACTIONS = set("mute", "unmute", "voice-on", "voice-off", "face-on", "face-off");

    /** Actions no plugin may run, whatever it was granted: they change who can control the robot. */
    static final Set<String> RESERVED_ACTIONS = set("pair", "phone-app-on", "phone-app-off");

    /**
     * Controller commands a plugin with DRIVE may send (docs/serial-protocol.md). Everything else, such as
     * shut-down, reset-wdt, debug or the power commands, is refused. The agent's motion guard still applies.
     */
    static final Set<String> DRIVE_CMDS = set("move", "head-angle", "head-shift", "head-dir", "mode", "projector",
            "arm", "lightsaber", "lcd");

    private PluginPermissions() {
    }

    /** Parses "status,actions" into a set; throws for unknown names. */
    static Set<String> parse(String list) {
        Set<String> s = new TreeSet<>();
        if (list == null) return s;
        for (String p : list.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (!ALL.containsKey(p)) throw new IllegalArgumentException("unknown permission \"" + p + "\" (known: " + ALL.keySet() + ")");
            s.add(p);
        }
        return s;
    }

    private static Set<String> set(String... names) {
        return Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(names)));
    }
}
