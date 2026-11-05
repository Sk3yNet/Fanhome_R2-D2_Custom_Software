package dev.r2hack.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Which permission level each API route needs (docs/security.md). Levels nest:
 * read &lt; control &lt; admin. Routes not listed here need admin, so a new route is locked down until
 * someone decides otherwise. The setup routes that work without any credentials during the pairing
 * button's window are decided in AgentService.openWithoutToken.
 *
 * Plugin scopes in R2 Main nest inside these: "status" is within read; "actions", "drive",
 * "routines.run", "routines.edit", "sound", "lights" and "settings" are within control. Nothing a plugin
 * can get is admin.
 */
final class Permissions {
    /** No credentials at all. */
    static final String OPEN = "open";

    private static final Map<String, String> ROUTES = new HashMap<>();

    static {
        open("GET /", "GET /ping", "GET /setup");
        read("GET /status", "GET /log", "GET /camera.mjpg", "GET /camera.jpg", "GET /routines", "GET /routine",
                "GET /wifi/last-join", "GET /mqtt", "GET /clients/allow", "GET /update", "GET /trust",
                "GET /whoami",
                // Anyone who may look at the robot may also stop it.
                "POST /stop");
        control("POST /action", "POST /routine", "POST /routine/delete", "POST /routine/run", "POST /routine/stop",
                "POST /volume", "POST /assist");
        // Everything else (installs, reboot, shutdown, adb, Wi-Fi, stock app, serial, simulator, logcat,
        // dmesg, clients, trust, updates, MQTT and subnet settings) is admin.
    }

    private static void open(String... routes) {
        for (String r : routes) ROUTES.put(r, OPEN);
    }

    private static void read(String... routes) {
        for (String r : routes) ROUTES.put(r, Clients.READ);
    }

    private static void control(String... routes) {
        for (String r : routes) ROUTES.put(r, Clients.CONTROL);
    }

    static String required(String method, String path) {
        String r = ROUTES.get(method + " " + path);
        return r == null ? Clients.ADMIN : r;
    }

    private Permissions() {}
}
