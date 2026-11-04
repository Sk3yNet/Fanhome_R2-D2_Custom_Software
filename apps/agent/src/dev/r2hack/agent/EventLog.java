package dev.r2hack.agent;

import android.util.Log;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Small in-memory ring buffer, readable over HTTP at /log, mirrored to a file so the
 * previous boot's log survives a crash or power loss (/log?previous=1).
 */
final class EventLog {
    private static final String TAG = "R2Agent";
    private static final int MAX = 500;
    private static final long MAX_FILE = 256 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat fileFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static File current, previous;
    private static OutputStream file;

    /** Call once at startup: the last boot's file becomes the "previous" log. */
    static synchronized void init(File dir) {
        current = new File(dir, "events.log");
        previous = new File(dir, "events.prev.log");
        if (current.exists()) {
            previous.delete();
            current.renameTo(previous);
        }
        openFile();
    }

    static synchronized void i(String msg) {
        Log.i(TAG, msg);
        Date now = new Date();
        if (lines.size() >= MAX) lines.removeFirst();
        lines.addLast(fmt.format(now) + " " + msg);
        if (file == null) return;
        try {
            // The clock can be wrong until network time arrives, so the uptime goes in too.
            file.write((fileFmt.format(now) + " +" + android.os.SystemClock.elapsedRealtime() / 1000 + "s "
                    + msg + "\n").getBytes(UTF8));
            file.flush();
            if (current.length() > MAX_FILE) openFile(); // keep only the newest part of this boot
        } catch (Exception e) {
            file = null;
        }
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        i("ERROR " + msg + (t != null ? ": " + t : ""));
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized String dumpPrevious() {
        if (previous == null || !previous.exists()) return "(no log from the previous boot)\n";
        try {
            FileInputStream in = new FileInputStream(previous);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), UTF8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "could not read previous log: " + e + "\n";
        }
    }

    private static void openFile() {
        try {
            if (file != null) file.close();
        } catch (Exception ignored) {
        }
        try {
            file = new FileOutputStream(current);
        } catch (Exception e) {
            file = null;
            Log.e(TAG, "event log file unavailable", e);
        }
    }

    private EventLog() {}
}
