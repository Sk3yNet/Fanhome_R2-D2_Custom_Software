package dev.r2hack.agent;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory ring buffer, readable over HTTP at /log. */
final class EventLog {
    private static final String TAG = "R2Agent";
    private static final int MAX = 500;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static synchronized void i(String msg) {
        Log.i(TAG, msg);
        if (lines.size() >= MAX) lines.removeFirst();
        lines.addLast(fmt.format(new Date()) + " " + msg);
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

    private EventLog() {}
}
