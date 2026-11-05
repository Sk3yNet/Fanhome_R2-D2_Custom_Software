package dev.r2hack.main;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What R2 Main knows about the robot: the controller's status (gin), its mode, network, and
 * identity (name, ID, paired phones). The name, ID and paired phones are what the official phone
 * app recognizes the robot by; they can be imported from the factory app (POST /action?name=...
 * is not enough, see Brain.importIdentity).
 */
final class Robot {
    interface Listener {
        void onRobotChanged();
    }

    // Modes, numbered as the phone app expects them.
    static final int READY = 1, SLEEP = 2, PAIR = 3, PATROL = 4, USER_CONTROL = 5;
    static final int LOW_BATTERY_PCT = 20; // the factory threshold for the low-battery lights
    private static final long CHARGING_DEBOUNCE_MS = 3000;

    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    volatile int battery = -1, charging, arm, lightsaber, projector, lcdS = 1, lcdL = 1;
    volatile String error = "";
    volatile int mode = READY;
    volatile boolean apMode;
    volatile boolean listening, faceSeen;   // voice awake / a face is being tracked (status lights)
    volatile int assistState;               // Home Assistant voice: Assist.IDLE, LISTENING, THINKING, SPEAKING
    volatile String ssid = "", ip = "";
    /** The agent's drive lock (docs/drive-lock.md): lock on, and driving enabled for now. Unknown = open. */
    volatile boolean driveLock, driveEnabled;
    volatile long driveSecondsLeft;

    /** May R2's wheels move now? The agent enforces this anyway; R2 Main checks it to react sensibly. */
    boolean driveAllowed() {
        return !driveLock || driveEnabled;
    }
    volatile Sounds sounds;           // for the mute state the phone app shows
    private volatile long chargingChangedAt;

    Robot(Context ctx) {
        prefs = ctx.getSharedPreferences("robot", Context.MODE_PRIVATE);
        if (prefs.getString("uuid", "").isEmpty()) {
            prefs.edit().putString("uuid", UUID.randomUUID().toString())
                    .putString("name", "R2D2-" + (System.currentTimeMillis() % 900 + 100)).apply();
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    String name() {
        return prefs.getString("name", "R2D2");
    }

    String uuid() {
        return prefs.getString("uuid", "");
    }

    boolean setName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) return false;
        prefs.edit().putString("name", name).apply();
        changed();
        return true;
    }

    boolean faceDetection() {
        return prefs.getBoolean("face_detection", true);
    }

    boolean voiceRecognition() {
        return prefs.getBoolean("voice_recognition", true);
    }

    void setFlag(String key, boolean on) {
        prefs.edit().putBoolean(key, on).apply();
        changed();
    }

    /** Paired phones: [{"uuid":...,"device_name":...}]. */
    JSONArray clients() {
        try {
            return new JSONArray(prefs.getString("clients", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    boolean isClient(String uuid) {
        JSONArray c = clients();
        for (int i = 0; i < c.length(); i++) if (uuid.equals(c.optJSONObject(i).optString("uuid"))) return true;
        return false;
    }

    void addClient(String uuid, String deviceName) {
        if (isClient(uuid)) return;
        try {
            JSONArray c = clients();
            c.put(new JSONObject().put("uuid", uuid).put("device_name", deviceName == null ? "" : deviceName));
            prefs.edit().putString("clients", c.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Removes one phone, or all of them if uuid is null. Returns false if it wasn't paired. */
    boolean removeClient(String uuid) {
        if (uuid == null) {
            prefs.edit().putString("clients", "[]").apply();
            return true;
        }
        JSONArray c = clients(), kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < c.length(); i++) {
            if (uuid.equals(c.optJSONObject(i).optString("uuid"))) found = true;
            else kept.put(c.optJSONObject(i));
        }
        prefs.edit().putString("clients", kept.toString()).apply();
        return found;
    }

    /** Takes over the factory app's identity, so the official phone app still knows this robot. */
    void importIdentity(String name, String uuid, JSONArray clients) {
        SharedPreferences.Editor e = prefs.edit();
        if (name != null && !name.isEmpty()) e.putString("name", name);
        if (uuid != null && !uuid.isEmpty()) e.putString("uuid", uuid);
        if (clients != null) e.putString("clients", clients.toString());
        e.apply();
        changed();
    }

    /**
     * Applies a gin report. Charging switching on applies at once; switching off waits 3 s, because the
     * contacts bounce (factory behavior). Returns true if anything the lights depend on changed.
     */
    boolean onGin(JSONObject g, final Runnable chargingSettled) {
        String before = snapshot();
        int oldBattery = battery, oldCharging = charging;
        battery = g.optInt("batt", battery);
        arm = g.optInt("arm", arm);
        lightsaber = g.optInt("lightsaber", lightsaber);
        projector = g.optInt("projector", projector);
        lcdS = g.optInt("lcd_s", lcdS);
        lcdL = g.optInt("lcd_l", lcdL);
        error = g.optString("error", error);
        final int c = g.optInt("charging-status", g.optInt("charging_status", 0));
        boolean lightsChanged = (oldBattery < LOW_BATTERY_PCT) != (battery < LOW_BATTERY_PCT) && oldBattery >= 0;
        if (c != oldCharging) {
            final long at = System.currentTimeMillis();
            chargingChangedAt = at;
            if (c == 1) {
                charging = 1;
                lightsChanged = true;
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(CHARGING_DEBOUNCE_MS);
                        } catch (InterruptedException ignored) {
                        }
                        if (chargingChangedAt != at) return; // bounced
                        charging = c;
                        changed();
                        chargingSettled.run();
                    }
                }, "charging-debounce").start();
            }
        }
        if (!before.equals(snapshot())) changed();
        return lightsChanged;
    }

    private String snapshot() {
        return battery + "," + charging + "," + arm + "," + lightsaber + "," + projector + "," + lcdS + "," + lcdL + "," + error;
    }

    void setMode(int m) {
        if (mode == m) return;
        mode = m;
        changed();
    }

    void changed() {
        for (Listener l : listeners) l.onRobotChanged();
    }

    /** The robot as the official phone app reads it. */
    JSONObject toPhoneJson() {
        try {
            return new JSONObject()
                    .put("arm", arm == 1).put("battery", Math.max(0, battery)).put("charging", charging)
                    .put("error", error).put("ip", ip).put("ap_mode", apMode)
                    .put("face_detection", faceDetection()).put("mute", sounds != null && sounds.muted())
                    .put("voice_recognition", voiceRecognition()).put("lightsaber", lightsaber == 1)
                    .put("lcd_l", lcdL >= 2).put("lcd_s", lcdS >= 2).put("mode", mode).put("name", name())
                    .put("projector", projector).put("self_update", 0).put("ssid", ssid)
                    .put("timestamp", System.currentTimeMillis()).put("update_dl_progress", 0)
                    .put("uuid", uuid()).put("version", 20);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
