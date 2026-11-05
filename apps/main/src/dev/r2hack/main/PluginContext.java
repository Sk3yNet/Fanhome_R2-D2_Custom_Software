package dev.r2hack.main;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * Everything a plugin can do to the robot, each call checked against the permissions it was granted
 * (PluginPermissions). Calls without the permission, and every call after the plugin was stopped, throw
 * {@link Denied}. Commands go the same way as everyone else's, so the agent's motion watchdog, low-battery
 * stop and emergency stop apply to plugins too.
 */
final class PluginContext {
    /** What R2 Main provides; implemented by Brain (and by a fake in the tests). */
    interface Host {
        /**
         * The robot's state: battery (int, -1 unknown), charging (0/1/2), lightsaber, arm, lcd_s, lcd_l
         * (booleans), projector (0/1/2), mode (string), action (string), error (string), muted, voice,
         * face (booleans), volume (0-100), liveliness, name, uuid, version, location (strings),
         * watch (WatchWithMe.status()).
         */
        JSONObject state();

        /** Whether name is one of Brain.ACTIONS. */
        boolean isAction(String name);

        void action(String name);

        /** Ends the running action or routine and stops the robot. */
        void stop();

        void projector(int mode);

        List<String> routineNames();

        /** Starts a stored routine; returns an error, or null. */
        String runRoutine(String name);

        /** Saves a routine; returns what happened, or throws Routine.Invalid. */
        String saveRoutine(JSONObject routine) throws Routine.Invalid;

        /** Deletes a routine; returns an error, or null. */
        String deleteRoutine(String name);

        /** One controller command (already checked against PluginPermissions.DRIVE_CMDS). */
        void controller(JSONObject cmd);

        void playSound(int number);

        void setVolume(int percent);

        void led(int r, int b, int y, int g);

        /** Taught places (docs: rooms), besides home. */
        List<String> placeNames();

        /** Sends R2 to a place, or "home". */
        void goTo(String place);

        /** A media player update for watch-with-me; returns what happened, or throws IllegalArgumentException. */
        String watchUpdate(JSONObject msg);

        /** The "Watch with me" switch. */
        void setWatch(boolean on);

        /** A named event for routines' "event" triggers (docs/routines.md); from: who sent it, for the log. */
        void routineEvent(String name, String from);

        /** The MQTT topic filters routines' "mqtt" triggers listen to. */
        java.util.Set<String> triggerTopics();

        /** A message on one of triggerTopics(); retained: replayed by the broker, not new. */
        void triggerMessage(String topic, String payload, boolean retained);

        void log(String msg);
    }

    /** A call the plugin may not make. */
    static final class Denied extends RuntimeException {
        Denied(String msg) {
            super(msg);
        }
    }

    private final String plugin;
    private final Host host;
    private final Set<String> granted;
    private final PluginManager.ConfigStore store;
    private volatile boolean open = true;

    PluginContext(String plugin, Host host, Set<String> granted, PluginManager.ConfigStore store) {
        this.plugin = plugin;
        this.host = host;
        this.granted = Collections.unmodifiableSet(new TreeSet<>(granted));
        this.store = store;
    }

    /** The permissions this plugin has now. */
    Set<String> granted() {
        return granted;
    }

    boolean has(String permission) {
        return open && granted.contains(permission);
    }

    /** Called when the plugin stops: every later call is refused. */
    void close() {
        open = false;
    }

    private void need(String permission, String what) {
        if (!open) throw new Denied(plugin + ": " + what + " refused, the plugin is stopped");
        if (!granted.contains(permission)) {
            throw new Denied(plugin + ": " + what + " needs the \"" + permission + "\" permission");
        }
    }

    // ---- status ----

    JSONObject state() {
        need(PluginPermissions.STATUS, "reading the state");
        return host.state();
    }

    // ---- actions, stop ----

    /** Runs a built-in action. Settings actions (mute, voice-on...) need "settings"; pairing and the phone app switch are never allowed. */
    void action(String name) {
        if ("stop".equals(name)) {
            stop();
            return;
        }
        if (!open) throw new Denied(plugin + ": action " + name + " refused, the plugin is stopped");
        if (PluginPermissions.RESERVED_ACTIONS.contains(name)) {
            throw new Denied(plugin + ": action " + name + " is not available to plugins");
        }
        if (!host.isAction(name)) throw new Denied(plugin + ": unknown action " + name);
        need(PluginPermissions.SETTING_ACTIONS.contains(name) ? PluginPermissions.SETTINGS : PluginPermissions.ACTIONS, "action " + name);
        host.log("plugin " + plugin + ": action " + name);
        host.action(name);
    }

    /** Stops the robot. Every running plugin may do this. */
    void stop() {
        if (!open) throw new Denied(plugin + ": stop refused, the plugin is stopped");
        host.log("plugin " + plugin + ": stop");
        host.stop();
    }

    /** 0 off, 1 Princess Leia, 2 Skywalker. */
    void projector(int mode) {
        need(PluginPermissions.ACTIONS, "projector");
        if (mode < 0 || mode > 2) throw new Denied(plugin + ": projector mode must be 0, 1 or 2");
        host.projector(mode);
    }

    // ---- places ----

    List<String> placeNames() {
        need(PluginPermissions.ACTIONS, "listing places");
        return host.placeNames();
    }

    /** Sends R2 to a taught place, or "home", like the go-home and tour actions. */
    void goTo(String place) {
        need(PluginPermissions.ACTIONS, "go to " + place);
        host.log("plugin " + plugin + ": go to " + place);
        host.goTo(place);
    }

    // ---- routines ----

    List<String> routineNames() {
        need(PluginPermissions.ROUTINES_RUN, "listing routines");
        return host.routineNames();
    }

    /** Returns an error, or null. */
    String runRoutine(String name) {
        need(PluginPermissions.ROUTINES_RUN, "routine " + name);
        host.log("plugin " + plugin + ": routine " + name);
        return host.runRoutine(name);
    }

    /** Sends a named event to routines' "event" triggers. */
    void routineEvent(String name) {
        need(PluginPermissions.ROUTINES_RUN, "event " + name);
        host.routineEvent(name, plugin);
    }

    /** The MQTT topics routines' "mqtt" triggers want; empty without "routines.run". */
    java.util.Set<String> triggerTopics() {
        if (!has(PluginPermissions.ROUTINES_RUN)) return java.util.Collections.emptySet();
        return host.triggerTopics();
    }

    /** A message on one of triggerTopics(), for routines' "mqtt" triggers. */
    void triggerMessage(String topic, String payload, boolean retained) {
        need(PluginPermissions.ROUTINES_RUN, "mqtt trigger " + topic);
        host.triggerMessage(topic, payload, retained);
    }

    String saveRoutine(JSONObject routine) throws Routine.Invalid {
        need(PluginPermissions.ROUTINES_EDIT, "saving a routine");
        host.log("plugin " + plugin + ": saving routine " + routine.optString("name"));
        return host.saveRoutine(routine);
    }

    /** Returns an error, or null. */
    String deleteRoutine(String name) {
        need(PluginPermissions.ROUTINES_EDIT, "deleting routine " + name);
        host.log("plugin " + plugin + ": deleting routine " + name);
        return host.deleteRoutine(name);
    }

    // ---- drive ----

    /** One controller command from PluginPermissions.DRIVE_CMDS, e.g. {"cmd":"move","power":40,"angle":0}. */
    void controller(JSONObject cmd) {
        String c = cmd == null ? "" : cmd.optString("cmd");
        need(PluginPermissions.DRIVE, "controller command " + c);
        if (!PluginPermissions.DRIVE_CMDS.contains(c)) {
            throw new Denied(plugin + ": controller command \"" + c + "\" is not available to plugins");
        }
        host.controller(cmd);
    }

    void drive(int power, int angle) {
        try {
            controller(new JSONObject().put("cmd", "move").put("power", power).put("angle", angle));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    void head(int angle) {
        try {
            controller(new JSONObject().put("cmd", "head-angle").put("angle", angle));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- sound, lights, settings ----

    void playSound(int number) {
        need(PluginPermissions.SOUND, "sound " + number);
        host.playSound(number);
    }

    /** Status lights, as the controller's led command: 0 unchanged, 1 off, 2 on, 3-6 flashing. */
    void led(int r, int b, int y, int g) {
        need(PluginPermissions.LIGHTS, "lights");
        for (int v : new int[] {r, b, y, g}) {
            if (v < 0 || v > 6) throw new Denied(plugin + ": led values are 0-6");
        }
        host.led(r, b, y, g);
    }

    void setVolume(int percent) {
        need(PluginPermissions.SETTINGS, "volume");
        if (percent < 0 || percent > 100) throw new Denied(plugin + ": volume is 0-100");
        host.setVolume(percent);
    }

    // ---- watch-with-me (docs/watch-with-me.md) ----

    /** What a film is doing (playing, paused, position...): R2 then reacts with his actions and sounds. */
    String watchUpdate(JSONObject msg) {
        need(PluginPermissions.ACTIONS, "watch update");
        return host.watchUpdate(msg);
    }

    /** The "Watch with me" switch: off keeps R2 quiet during films. */
    void setWatch(boolean on) {
        need(PluginPermissions.SETTINGS, "watch-with-me switch");
        host.setWatch(on);
    }

    // ---- always allowed ----

    void log(String msg) {
        host.log("plugin " + plugin + ": " + msg);
    }

    /** Stores the plugin's own config (it gets it back in start after a restart). */
    void saveConfig(JSONObject config) throws Exception {
        if (!open) throw new Denied(plugin + ": saving config refused, the plugin is stopped");
        store.save(plugin, config);
    }
}
