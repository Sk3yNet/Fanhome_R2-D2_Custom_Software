package dev.r2hack.main;

import android.util.Log;
import org.json.JSONObject;

/**
 * Robot behavior goes here. Commands are listed in notes/serial-protocol.md.
 * Example: brainService.send(new JSONObject().put("cmd", "mode").put("mode", 15)); // shake head
 */
final class Brain {
    private static final String TAG = "R2Brain";
    private final BrainService io;

    Brain(BrainService io) {
        this.io = io;
    }

    /** Called each time the bridge connection to the agent is (re)established. */
    void onConnected() {
        Log.i(TAG, "connected");
    }

    /** Every message from the controller board: gin status, btn presses, play_sound requests... */
    void onControllerMessage(JSONObject msg) {
        String cmd = msg.optString("cmd");
        if ("btn".equals(cmd)) {
            Log.i(TAG, "button " + msg.optInt("value"));
        } else if ("play_sound".equals(cmd)) {
            Log.i(TAG, "controller asked for sound " + msg.optInt("sound_id"));
        }
    }
}
