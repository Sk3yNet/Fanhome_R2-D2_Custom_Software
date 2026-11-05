package dev.r2hack.main;

import java.util.Set;
import org.json.JSONObject;

/**
 * A plugin: an integration or behavior built into R2 Main that reacts to the robot's events and acts on
 * the robot only through its {@link PluginContext}, which checks the permissions the plugin was granted.
 * See docs/plugins.md.
 *
 * Lifecycle: {@link #start} when the plugin is enabled (at startup, or later through POST /plugin),
 * {@link #configure} each time its config changes, {@link #onEvent} for each event while it runs, and
 * {@link #stop} when it is disabled. After stop the context refuses every call, so a thread the plugin
 * forgot can't keep acting on the robot. Events arrive on the plugin's own thread, one at a time, so a
 * slow plugin never holds up R2 Main or other plugins. An exception from a plugin is logged; after
 * {@link PluginManager#MAX_FAILURES} of them in a row the plugin is stopped.
 *
 * Plugins must not use Android classes they don't need, so they can be tested on a plain JVM.
 */
interface Plugin {
    /** Events (the type passed to onEvent). Plugins with the "status" permission get all of them. */
    String EVENT_STATE = "state";        // the robot's state changed; data = PluginContext.state()
    String EVENT_RUNNING = "running";    // the running action or routine changed; data {"name": ..., "last_routine": ...}
    String EVENT_BUTTON = "button";      // a robot button was pressed; data {"value": 1-6} (docs/serial-protocol.md)
    String EVENT_ROUTINES = "routines";  // a routine was saved or deleted; data {"names": [...]}

    /** Lower-case letters, digits and -, unique. Used in the API, e.g. POST /plugin?name=home-assistant. */
    String name();

    /** One line for GET /plugins. */
    String description();

    /** The permissions the plugin asks for (PluginPermissions). It is never given any others. */
    Set<String> permissions();

    /** Whether the plugin runs on a robot that has never been told otherwise. */
    boolean enabledByDefault();

    /** The plugin was enabled. config is its stored config (an empty object if it has none yet). */
    void start(PluginContext ctx, JSONObject config) throws Exception;

    /** Its config changed (POST /plugin/config) while it runs. */
    void configure(JSONObject config) throws Exception;

    /** An event while it runs; see the EVENT_ types. Only called with the "status" permission. */
    void onEvent(String type, JSONObject data) throws Exception;

    /** The plugin was disabled, or R2 Main stops. Ends its threads and connections. */
    void stop();

    /** One line for GET /plugins and the status page, e.g. "connected to 192.168.1.10". */
    String status();
}
