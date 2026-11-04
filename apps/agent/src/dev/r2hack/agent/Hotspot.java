package dev.r2hack.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Settings for the robot's own hotspot: the factory hotspot name with a password made for this robot,
 * instead of the factory default that every robot shares.
 *
 * The factory settings (whatever Android's hotspot settings held the first time this agent ran) are
 * kept, and used:
 * - in passive mode (stock app switched back on), so the factory software sees its own hotspot;
 * - in the pairing button's setup window, as the way back in when the robot's password is forgotten
 *   (and for the official phone app's setup, which expects the factory password);
 * - restored into Android's settings by POST /stock?enable=1.
 *
 * The password is kept in the agent's preferences and in files/hotspot-password (owner only), so it can
 * also be read over the serial console or root adb.
 */
final class Hotspot {
    /** 32 characters (a power of two, so no bias), without the look-alikes l, o, 0 and 1. */
    static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    static final String OWN_PATTERN = "[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}";
    static final String DEFAULT_SSID = "R2-D2";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final WifiManager wm;
    private final SharedPreferences prefs;
    private final File passwordFile;
    private final boolean passive;
    private volatile long factoryUntil;

    Hotspot(Context ctx, WifiManager wm, boolean passive) {
        this.wm = wm;
        this.passive = passive;
        prefs = ctx.getSharedPreferences("hotspot", Context.MODE_PRIVATE);
        passwordFile = new File(ctx.getFilesDir(), "hotspot-password");
        init();
    }

    /** Saves the factory settings once (before this agent ever changes them) and makes this robot's password. */
    private void init() {
        if (!prefs.getBoolean("factory_saved", false)) {
            WifiConfiguration cfg = null;
            try {
                Method get = WifiManager.class.getMethod("getWifiApConfiguration");
                cfg = (WifiConfiguration) get.invoke(wm);
            } catch (Exception e) {
                EventLog.e("hotspot: could not read the factory settings", e);
            }
            String ssid = cfg == null || cfg.SSID == null ? "" : cfg.SSID;
            String psk = cfg == null || cfg.preSharedKey == null ? "" : cfg.preSharedKey;
            SharedPreferences.Editor ed = prefs.edit().putString("factory_ssid", ssid);
            if (psk.matches(OWN_PATTERN)) {
                // Our own password is already in Android's settings (the agent's data was wiped): the factory
                // password is gone. Keep using ours; the setup window then runs an open hotspot instead.
                ed.putString("factory_psk", "").putString("password", psk);
                EventLog.i("hotspot: factory password unknown (settings already ours); setup window will be open");
            } else {
                ed.putString("factory_psk", psk);
            }
            ed.putBoolean("factory_saved", true).commit();
            EventLog.i("hotspot: saved the factory settings (" + (ssid.isEmpty() ? "no name" : ssid) + ")");
        }
        if (password().isEmpty()) {
            setPassword(generate(new SecureRandom()));
            EventLog.i("hotspot: made this robot's hotspot password (tools\\r2.ps1 hotspot)");
        } else {
            writeFile(password());
        }
    }

    /** The name both the factory and our own settings use. */
    String ssid() {
        String s = prefs.getString("factory_ssid", "");
        return s.isEmpty() ? DEFAULT_SSID : s;
    }

    /** This robot's own password. */
    String password() {
        return prefs.getString("password", "");
    }

    void setPassword(String pw) {
        prefs.edit().putString("password", pw).commit();
        writeFile(pw);
    }

    /** The factory password, or "" when it's unknown or the factory hotspot was open. */
    String factoryPassword() {
        return prefs.getString("factory_psk", "");
    }

    /** True when the hotspot runs (or would run) with the factory settings. */
    boolean usingFactory() {
        return passive || factorySecondsLeft() > 0;
    }

    long factorySecondsLeft() {
        return Math.max(0, (factoryUntil - SystemClock.elapsedRealtime() + 999) / 1000);
    }

    void openFactoryWindow(long ms) {
        factoryUntil = SystemClock.elapsedRealtime() + ms;
    }

    /** True if switching between factory and own settings changes anything a phone would notice. */
    boolean factoryDiffers() {
        return !factoryPassword().equals(password());
    }

    /** What to start the hotspot with right now. */
    WifiConfiguration config() {
        return usingFactory() ? build(ssid(), factoryPassword()) : build(ssid(), password());
    }

    /** Puts the factory settings back into Android's settings, for the factory software (stock-on). */
    void restoreFactory() {
        try {
            Method set = WifiManager.class.getMethod("setWifiApConfiguration", WifiConfiguration.class);
            set.invoke(wm, build(ssid(), factoryPassword()));
            EventLog.i("hotspot: factory settings restored");
        } catch (Exception e) {
            EventLog.e("hotspot: could not restore the factory settings", e);
        }
    }

    private static WifiConfiguration build(String ssid, String password) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = ssid; // hotspot settings take the raw name and key, without the quotes station settings use
        if (password.isEmpty()) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            c.allowedKeyManagement.set(wpa2Psk());
            c.preSharedKey = password;
        }
        return c;
    }

    /** KeyMgmt.WPA2_PSK is hidden in the public SDK (it is 4 on KitKat). */
    private static int wpa2Psk() {
        try {
            return WifiConfiguration.KeyMgmt.class.getField("WPA2_PSK").getInt(null);
        } catch (Exception e) {
            return 4;
        }
    }

    private void writeFile(String pw) {
        try {
            FileOutputStream out = new FileOutputStream(passwordFile);
            out.write(pw.getBytes(UTF8));
            out.close();
            passwordFile.setReadable(false, false); // owner only
            passwordFile.setReadable(true, true);
        } catch (Exception e) {
            EventLog.e("could not write files/hotspot-password", e);
        }
    }

    // ---- pure helpers (tested off the robot, apps/test.sh) ----------------

    /** A new password like "k7mq-x3fa-9tpe": 12 random characters (60 bits), easy to read and type. */
    static String generate(Random rnd) {
        byte[] b = new byte[12];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < b.length; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET.charAt(b[i] & 31));
        }
        return sb.toString();
    }

    /** Why pw can't be a WPA2 password, or null if it can: 8 to 63 printable ASCII characters. */
    static String problem(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 63) return "a hotspot password has 8 to 63 characters";
        for (int i = 0; i < pw.length(); i++) {
            char ch = pw.charAt(i);
            if (ch < 0x20 || ch > 0x7e) return "a hotspot password may only contain printable ASCII characters";
        }
        return null;
    }
}
