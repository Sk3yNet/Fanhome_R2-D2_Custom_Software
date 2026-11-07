package dev.r2hack.agent;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs shell commands as root for the agent.
 *
 * R2's /system/bin/su is a Superuser client that needs a daemon and an app the robot doesn't have, so it fails
 * (exit 255, no output) for every caller. Root comes instead from r2-rootd.sh, a small helper that Android starts
 * as root at every boot through /system/etc/install-recovery.sh (both installed by tools/install-on-robot.ps1;
 * source in apps/agent/system/). The helper only takes requests from the agent's own private folder
 * files/rootq (mode 0700, owned by the agent's user), so other apps can't use it.
 *
 * A request is a shell script written as <id>.part and renamed to <id>.req. The helper renames it to <id>.run,
 * runs it, and leaves the output in <id>.out and the exit code in <id>.rc. If the helper doesn't pick a request
 * up within a few seconds it isn't running, and this falls back to "su -c" (for a build whose su works).
 */
final class Root {
    private static final long PICKUP_MS = 3_000;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final AtomicLong SEQ = new AtomicLong();

    private static volatile File queue;
    private static volatile String via = "unknown";

    private Root() {
    }

    /** At agent start: creates the private request folder. */
    static void init(Context ctx) {
        File q = new File(ctx.getFilesDir(), "rootq");
        if (!q.isDirectory() && !q.mkdirs()) EventLog.i("root: could not create " + q);
        privateDir(q);
        File[] old = q.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.getName().endsWith(".run")) delete(f); // .run: the helper may still be working on it
            }
        }
        queue = q;
    }

    /** "helper", "su", "none" or "unknown" (nothing asked for yet), for /status. */
    static String via() {
        return via;
    }

    /** The command's output (stdout and stderr), or "error: ..." when it couldn't be run as root. */
    static String run(String script) {
        Result r = exec(script, DEFAULT_TIMEOUT_MS);
        return r.output;
    }

    /** Runs the script as root and returns its exit code and output; exit code -1 if it couldn't run. */
    static Result exec(String script, long timeoutMs) {
        File q = queue;
        if (q != null) {
            Result r = viaHelper(q, script, timeoutMs);
            if (r != null) {
                via = "helper";
                return r;
            }
        }
        Result r = viaSu(script);
        if (r.code == 0 || !r.output.isEmpty()) {
            via = "su";
            return r;
        }
        via = "none";
        String msg = "error: no root (helper r2-rootd not running, su exit " + r.code + ")";
        EventLog.i("root: " + msg);
        return new Result(-1, msg);
    }

    /** null when the helper didn't pick the request up (not running). */
    private static Result viaHelper(File q, String script, long timeoutMs) {
        String id = "r" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
        File part = new File(q, id + ".part"), req = new File(q, id + ".req");
        File out = new File(q, id + ".out"), rc = new File(q, id + ".rc");
        try {
            FileOutputStream o = new FileOutputStream(part);
            try {
                o.write(script.getBytes("UTF-8"));
                o.write('\n');
            } finally {
                o.close();
            }
            if (!part.renameTo(req)) {
                delete(part);
                return null;
            }
            long start = System.currentTimeMillis();
            while (true) {
                if (out.exists()) {
                    String text = read(out).trim();
                    int code = parseCode(read(rc));
                    delete(out);
                    delete(rc);
                    return new Result(code, text);
                }
                long waited = System.currentTimeMillis() - start;
                if (req.exists() && waited >= PICKUP_MS) {
                    delete(req); // never picked up: no helper
                    return null;
                }
                if (waited >= timeoutMs) return new Result(-1, "error: root command timed out after " + waited + " ms");
                Thread.sleep(100);
            }
        } catch (Exception e) {
            delete(part);
            delete(req);
            return null;
        }
    }

    private static Result viaSu(String script) {
        try {
            Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            String text = readAll(p.getInputStream()).trim();
            return new Result(p.waitFor(), text);
        } catch (Exception e) {
            return new Result(-1, "");
        }
    }

    static int parseCode(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void privateDir(File d) {
        d.setReadable(false, false);
        d.setWritable(false, false);
        d.setExecutable(false, false);
        d.setReadable(true, true);
        d.setWritable(true, true);
        d.setExecutable(true, true);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void delete(File f) {
        f.delete();
    }

    private static String read(File f) throws Exception {
        if (!f.exists()) return "";
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }
}
