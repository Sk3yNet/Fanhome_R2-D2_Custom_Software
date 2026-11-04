package dev.r2hack.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * API credentials. Each robot makes its own random token on first start, so a published build
 * carries no secret. It is stored in the agent's private files (files/api-token), readable only
 * with root, e.g. over the serial console or root adb (tools\r2.ps1 fetch-token).
 * A token built into the APK (Secrets.TOKEN, from secrets\agent-token.txt) is still accepted
 * for your own builds; release builds leave it empty.
 */
final class Credentials {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile String robotToken = "";

    static void init(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE);
        String t = prefs.getString("token", "");
        if (t.isEmpty()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            t = sb.toString();
            prefs.edit().putString("token", t).commit();
            EventLog.i("created this robot's API token (tools\\r2.ps1 fetch-token)");
        }
        robotToken = t;
        try {
            File f = new File(ctx.getFilesDir(), "api-token");
            FileOutputStream out = new FileOutputStream(f);
            out.write(t.getBytes(UTF8));
            out.close();
            f.setReadable(false, false); // owner only
            f.setReadable(true, true);
        } catch (Exception e) {
            EventLog.e("could not write files/api-token", e);
        }
    }

    /** This robot's own token (handed out only in the setup window, see AgentService). */
    static String robotToken() {
        return robotToken;
    }

    static boolean accepts(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        return same(presented, robotToken) || (!Secrets.TOKEN.isEmpty() && same(presented, Secrets.TOKEN));
    }

    /** Constant-time comparison, so response timing doesn't leak the token. */
    private static boolean same(String a, String b) {
        return !b.isEmpty() && MessageDigest.isEqual(a.getBytes(UTF8), b.getBytes(UTF8));
    }

    private Credentials() {}
}
