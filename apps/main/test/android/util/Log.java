package android.util;

/** Test stand-in for Android's Log, whose real methods are native. Prints to stderr. */
public final class Log {
    private Log() {
    }

    private static int print(String level, String tag, String msg, Throwable t) {
        System.err.println(level + "/" + tag + ": " + msg + (t != null ? " (" + t + ")" : ""));
        return 0;
    }

    public static int v(String tag, String msg) { return print("V", tag, msg, null); }
    public static int d(String tag, String msg) { return print("D", tag, msg, null); }
    public static int i(String tag, String msg) { return print("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return print("I", tag, msg, t); }
    public static int w(String tag, String msg) { return print("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return print("W", tag, msg, t); }
    public static int w(String tag, Throwable t) { return print("W", tag, "", t); }
    public static int e(String tag, String msg) { return print("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return print("E", tag, msg, t); }
}
