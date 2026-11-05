package dev.r2hack.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * Plays the robot's droid sounds by number. Nothing is bundled: the sounds are read at runtime from
 * the factory app already installed on the robot, the same way the agent loads the serial library.
 * The number -> sound mapping comes from assets/sounds.json ({"7": "<resource name>", ...}),
 * which is not part of the repository.
 */
final class Sounds {
    private static final String TAG = "R2Sounds";
    private static final String STOCK_PACKAGE = "com.bullb.r2d2_nanopisystem";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Map<Integer, Integer> ids = new HashMap<>();
    private Resources stock;
    private String problem = "not loaded";
    private MediaPlayer player;
    /** Called after the volume or mute changes, from wherever (web page, phone app, Home Assistant). */
    volatile Runnable onChange;

    Sounds(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE);
        load();
        applyVolume();
    }

    String status() {
        return problem != null ? "sounds unavailable: " + problem
                : ids.size() + " sounds, " + (muted() ? "muted" : "volume " + volume() + "%");
    }

    boolean muted() {
        return prefs.getBoolean("muted", false);
    }

    int volume() {
        return prefs.getInt("volume", 70); // the factory default
    }

    void setMuted(boolean on) {
        prefs.edit().putBoolean("muted", on).apply();
        if (on) stop();
        applyVolume();
        changed();
    }

    void setVolume(int percent) {
        prefs.edit().putInt("volume", Math.max(0, Math.min(100, percent))).apply();
        applyVolume();
        changed();
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    /** Starts a sound. interrupt=false leaves a sound that is already playing alone. */
    synchronized boolean play(int number, boolean interrupt) {
        if (muted()) return false;
        if (!interrupt && isPlaying()) return false;
        Integer res = ids.get(number);
        if (res == null) {
            Log.w(TAG, "no sound " + number + " (" + status() + ")");
            return false;
        }
        stop();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            setSource(mp, res, number);
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not play sound " + number, e);
            return false;
        }
    }

    /** Plays a sound and waits for it to finish (or for maxMs). Returns false if it didn't play. */
    boolean playAndWait(int number, long maxMs) throws InterruptedException {
        if (!play(number, true)) return false;
        long end = System.currentTimeMillis() + maxMs;
        try {
            while (isPlaying() && System.currentTimeMillis() < end) Thread.sleep(100);
        } catch (InterruptedException e) {
            stop();
            throw e;
        }
        return true;
    }

    /** The sound numbers this build can play (from assets/sounds.json, found in the factory app). */
    java.util.Set<Integer> numbers() {
        return new java.util.HashSet<>(ids.keySet());
    }

    synchronized boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    synchronized void stop() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    private void applyVolume() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, muted() ? 0 : Math.round(max * volume() / 100f), 0);
    }

    private void setSource(MediaPlayer mp, int res, int number) throws Exception {
        AssetFileDescriptor fd = null;
        try {
            fd = stock.openRawResourceFd(res);
        } catch (Exception compressed) {
            // fall through: copy it out instead
        }
        if (fd != null) {
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            return;
        }
        File f = new File(ctx.getCacheDir(), "sound-" + number);
        if (!f.exists()) {
            InputStream in = stock.openRawResource(res);
            FileOutputStream out = new FileOutputStream(f);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
        }
        mp.setDataSource(f.getPath());
    }

    private void load() {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(STOCK_PACKAGE, 0);
            stock = ctx.getPackageManager().getResourcesForApplication(ai);
        } catch (Exception e) {
            problem = "factory app not found";
            return;
        }
        JSONObject map;
        try {
            InputStream in = ctx.getAssets().open("sounds.json");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            map = new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            problem = "assets/sounds.json missing from this build";
            return;
        }
        Iterator<?> keys = map.keys();
        while (keys.hasNext()) {
            String k = (String) keys.next();
            int res = stock.getIdentifier(map.optString(k), "raw", STOCK_PACKAGE);
            if (res != 0) ids.put(Integer.parseInt(k), res);
            else Log.w(TAG, "sound " + k + " not found in the factory app");
        }
        problem = ids.isEmpty() ? "none of the mapped sounds were found" : null;
    }
}
