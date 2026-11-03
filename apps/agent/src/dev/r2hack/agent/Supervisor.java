package dev.r2hack.agent;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Keeps the main app running and protects against bad builds:
 *  - main app sends {"agent":"heartbeat"} every ~2s over the bridge;
 *  - silence for HEARTBEAT_TIMEOUT_MS = death: the robot is told to stop, the app relaunched;
 *  - CRASH_LIMIT deaths in CRASH_WINDOW_MS: a build still on probation is rolled back
 *    to the last good APK, otherwise the agent enters safe mode (no relaunches);
 *  - a newly installed build becomes "last good" after PROBATION_MS of heartbeats.
 */
final class Supervisor {
    interface StopHook {
        void stopRobot();
    }

    private final Context ctx;
    private final StopHook stopHook;
    private final SharedPreferences prefs;
    private final File lastGoodApk;
    private final File uploadDir;
    private final ArrayDeque<Long> deaths = new ArrayDeque<>();
    private final long startedAt = System.currentTimeMillis();

    private volatile long lastHeartbeat;
    private volatile long aliveSince;
    private volatile long lastLaunch;
    private volatile boolean alive;
    private volatile String lastEvent = "starting";

    Supervisor(Context ctx, StopHook stopHook) {
        this.ctx = ctx;
        this.stopHook = stopHook;
        this.prefs = ctx.getSharedPreferences("supervisor", Context.MODE_PRIVATE);
        this.lastGoodApk = new File(ctx.getFilesDir(), "main-lastgood.apk");
        this.uploadDir = new File(ctx.getFilesDir(), "upload");
        uploadDir.mkdirs();
        world(ctx.getFilesDir(), true);
        world(uploadDir, true);
    }

    File uploadDir() {
        return uploadDir;
    }

    boolean safeMode() {
        return prefs.getBoolean("safe", false);
    }

    void setSafeMode(boolean on) {
        prefs.edit().putBoolean("safe", on).apply();
        synchronized (deaths) {
            deaths.clear();
        }
        event(on ? "safe mode ON" : "safe mode OFF");
        if (on) {
            forceStopMain();
            stopHook.stopRobot();
        }
    }

    boolean onProbation() {
        return prefs.getBoolean("probation", false);
    }

    void onHeartbeat() {
        long now = System.currentTimeMillis();
        if (!alive) {
            alive = true;
            aliveSince = now;
            event("main app alive");
        }
        lastHeartbeat = now;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        tick();
                    } catch (Throwable e) {
                        EventLog.e("supervisor tick", e);
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "supervisor");
        t.setDaemon(true);
        t.start();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (alive && now - lastHeartbeat > Config.HEARTBEAT_TIMEOUT_MS) {
            alive = false;
            event("main app heartbeat lost");
            stopHook.stopRobot();
            recordDeath(now);
        }
        if (alive && (onProbation() || !hasLastGood()) && now - aliveSince > Config.PROBATION_MS) {
            markGood();
        }
        if (alive || safeMode() || !isInstalled(Config.MAIN_PACKAGE)) return;
        if (now - startedAt < Config.FIRST_LAUNCH_DELAY_MS) return;
        if (now - lastLaunch < Config.RELAUNCH_DELAY_MS + Config.HEARTBEAT_TIMEOUT_MS) return;
        if (lastLaunch != 0 && lastHeartbeat < lastLaunch) {
            // launched but never sent a heartbeat: counts as a death too
            recordDeath(now);
            if (safeMode()) return;
        }
        launchMain();
    }

    private void recordDeath(long now) {
        int n;
        synchronized (deaths) {
            deaths.addLast(now);
            Iterator<Long> it = deaths.iterator();
            while (it.hasNext()) {
                if (now - it.next() > Config.CRASH_WINDOW_MS) it.remove();
            }
            n = deaths.size();
        }
        event("main app death " + n + "/" + Config.CRASH_LIMIT);
        if (n < Config.CRASH_LIMIT) return;
        synchronized (deaths) {
            deaths.clear();
        }
        if (onProbation() && lastGoodApk.isFile()) {
            event("crash loop on new build -> rolling back");
            String r = pmInstall(lastGoodApk, true);
            prefs.edit().putBoolean("probation", false).apply();
            event("rollback result: " + r);
        } else {
            event("crash loop -> safe mode (send POST /safe-mode?on=0 to resume)");
            prefs.edit().putBoolean("safe", true).apply();
        }
    }

    private void markGood() {
        prefs.edit().putBoolean("probation", false).apply();
        try {
            String src = ctx.getPackageManager().getApplicationInfo(Config.MAIN_PACKAGE, 0).sourceDir;
            copy(new File(src), lastGoodApk);
            world(lastGoodApk, false);
            event("build marked good: " + mainVersion());
        } catch (Exception e) {
            EventLog.e("markGood failed", e);
        }
    }

    void launchMain() {
        lastLaunch = System.currentTimeMillis();
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(Config.MAIN_PACKAGE);
        if (i == null) {
            event("main app has no launcher activity");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(i);
        event("launched main app");
    }

    /** Installs an uploaded main-app APK and puts it on probation. */
    synchronized String installMain(File apk) {
        world(apk, false);
        forceStopMain();
        String r = pmInstall(apk, false);
        if (r.contains("Success")) {
            prefs.edit().putBoolean("probation", true).putBoolean("safe", false).apply();
            synchronized (deaths) {
                deaths.clear();
            }
            alive = false;
            lastLaunch = 0;
            event("installed main " + mainVersion() + ", on probation");
        } else {
            event("main install failed: " + r);
        }
        apk.delete();
        return r;
    }

    synchronized String rollback() {
        if (!lastGoodApk.isFile()) return "no last-good APK stored";
        forceStopMain();
        String r = pmInstall(lastGoodApk, true);
        prefs.edit().putBoolean("probation", false).apply();
        lastLaunch = 0;
        event("manual rollback: " + r);
        return r;
    }

    /** Self-update; the agent process is killed by the install, the system restarts it. */
    String installAgent(File apk) {
        world(apk, false);
        event("installing agent update; agent will restart");
        return pmInstall(apk, false);
    }

    String mainVersion() {
        return version(Config.MAIN_PACKAGE);
    }

    String version(String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    boolean mainAlive() {
        return alive;
    }

    boolean hasLastGood() {
        return lastGoodApk.isFile();
    }

    String lastEvent() {
        return lastEvent;
    }

    private boolean isInstalled(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void forceStopMain() {
        run("am", "force-stop", Config.MAIN_PACKAGE);
        alive = false;
    }

    /** Returns pm's last line ("Success" or "Failure [...]"); KitKat's pm also prints a harmless fsync trace. */
    private String pmInstall(File apk, boolean allowDowngrade) {
        String out = allowDowngrade
                ? run("pm", "install", "-r", "-d", apk.getAbsolutePath())
                : run("pm", "install", "-r", apk.getAbsolutePath());
        return out.substring(out.lastIndexOf('\n') + 1);
    }

    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    private void event(String msg) {
        lastEvent = msg;
        EventLog.i(msg);
    }

    /** The package manager reads the APK as another uid, so files and dirs must be world-readable. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void world(File f, boolean dir) {
        f.setReadable(true, false);
        if (dir) f.setExecutable(true, false);
    }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }
}
