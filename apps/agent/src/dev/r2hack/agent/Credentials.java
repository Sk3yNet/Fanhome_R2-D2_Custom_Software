package dev.r2hack.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;

/**
 * The robot owner's key. Each robot makes its own random key on first start, so a published build
 * carries no secret. It is stored in the agent's private files (files/api-token), readable only
 * with root, e.g. over the serial console or root adb (tools\r2.ps1 fetch-token), and handed out without
 * credentials only in the pairing button's setup window. It signs requests as the built-in "owner"
 * client (Clients, RequestAuth). A token built into the APK (Secrets.TOKEN, from secrets\agent-token.txt)
 * is the "dev" client's key in your own builds; release builds leave it empty.
 */
final class Credentials {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile String robotToken = "";
    private static SharedPreferences prefs;
    private static File tokenFile;

    static void init(Context ctx) {
        prefs = ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE);
        tokenFile = new File(ctx.getFilesDir(), "api-token");
        String t = prefs.getString("token", "");
        if (t.isEmpty()) {
            t = store(newToken());
            EventLog.i("created this robot's API token (tools\\r2.ps1 fetch-token)");
        }
        robotToken = t;
        writeFile(t);
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static String store(String t) {
        prefs.edit().putString("token", t).commit();
        return t;
    }

    private static void writeFile(String t) {
        try {
            FileOutputStream out = new FileOutputStream(tokenFile);
            out.write(t.getBytes(UTF8));
            out.close();
            tokenFile.setReadable(false, false); // owner only
            tokenFile.setReadable(true, true);
        } catch (Exception e) {
            EventLog.e("could not write files/api-token", e);
        }
    }

    /** This robot's own token (handed out only in the setup window, see AgentService). */
    static String robotToken() {
        return robotToken;
    }

    /** A new owner key, e.g. after the old one went over the network in plain text. */
    static synchronized String rotate() {
        String t = store(newToken());
        robotToken = t;
        writeFile(t);
        EventLog.i("owner key rotated");
        return t;
    }

    private Credentials() {}
}
