package dev.r2hack.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads, starts and stops the plugins built into R2 Main, keeps their settings (enabled, granted
 * permissions, config) in one JSON file, and hands them events. Each running plugin has its own thread
 * and event queue. See docs/plugins.md.
 *
 * The agent's plugin API is passed here over the bridge:
 * {"agent":"plugins","seq":N,"op":"list|enable|disable|grant|config",...}, answered by
 * {"agent":"plugins-reply","seq":N,"ok":true|false,...}.
 *
 * No Android classes, so it is tested on a plain JVM (apps/test.sh).
 */
final class PluginManager {
    /** Exceptions in a row after which a plugin is stopped (until it is enabled again or R2 Main restarts). */
    static final int MAX_FAILURES = 5;
    /** Events waiting for one plugin; more are dropped (state events are merged anyway). */
    static final int QUEUE_LIMIT = 100;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] SECRET_WORDS = {"password", "token", "secret", "key"};

    /** Where a plugin's context saves its config. */
    interface ConfigStore {
        void save(String plugin, JSONObject config) throws Exception;
    }

    private final File file;
    private final PluginContext.Host host;
    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private final Map<String, Runner> running = new LinkedHashMap<>();
    private final Map<String, String> lastStatus = new LinkedHashMap<>();
    private JSONObject settings;

    PluginManager(File file, PluginContext.Host host, List<Plugin> available) {
        this.file = file;
        this.host = host;
        for (Plugin p : available) {
            if (!p.name().matches("[a-z0-9][a-z0-9-]{0,31}")) throw new IllegalArgumentException("bad plugin name " + p.name());
            for (String perm : p.permissions()) {
                if (!PluginPermissions.ALL.containsKey(perm)) throw new IllegalArgumentException(p.name() + ": unknown permission " + perm);
            }
            if (plugins.put(p.name(), p) != null) throw new IllegalArgumentException("two plugins named " + p.name());
        }
        settings = load();
    }

    // ---- settings ---------------------------------------------------------------------------

    private JSONObject load() {
        try {
            if (file.isFile()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                }
                return new JSONObject(new String(b.toByteArray(), UTF8));
            }
        } catch (Exception e) {
            host.log("plugins: " + file + " unreadable, starting from defaults: " + e);
        }
        return new JSONObject();
    }

    private void persist() throws Exception {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("can't create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(settings.toString(2).getBytes(UTF8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new Exception("can't replace " + file);
    }

    private JSONObject entry(String name) throws Exception {
        JSONObject e = settings.optJSONObject(name);
        if (e == null) {
            e = new JSONObject();
            settings.put(name, e);
        }
        return e;
    }

    synchronized boolean enabled(String name) {
        JSONObject e = settings.optJSONObject(name);
        Plugin p = plugins.get(name);
        return e != null && e.has("enabled") ? e.optBoolean("enabled") : p != null && p.enabledByDefault();
    }

    /** What the plugin may do: what it asks for, minus what the owner took away. */
    synchronized Set<String> granted(String name) {
        Plugin p = plugins.get(name);
        Set<String> g = new TreeSet<>(p.permissions());
        JSONObject e = settings.optJSONObject(name);
        JSONArray a = e == null ? null : e.optJSONArray("granted");
        if (a != null) {
            Set<String> chosen = new TreeSet<>();
            for (int i = 0; i < a.length(); i++) chosen.add(a.optString(i));
            g.retainAll(chosen);
        }
        return g;
    }

    synchronized JSONObject config(String name) {
        JSONObject e = settings.optJSONObject(name);
        JSONObject c = e == null ? null : e.optJSONObject("config");
        try {
            return c == null ? new JSONObject() : new JSONObject(c.toString());
        } catch (Exception bad) {
            return new JSONObject();
        }
    }

    private final ConfigStore store = new ConfigStore() {
        @Override
        public void save(String plugin, JSONObject config) throws Exception {
            synchronized (PluginManager.this) {
                entry(plugin).put("config", new JSONObject(config.toString()));
                persist();
            }
        }
    };

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Starts every enabled plugin. */
    synchronized void startAll() {
        for (String name : plugins.keySet()) {
            if (enabled(name)) start(name);
        }
    }

    synchronized void stopAll() {
        for (String name : new ArrayList<>(running.keySet())) stop(name, "stopped");
    }

    synchronized boolean isRunning(String name) {
        return running.containsKey(name);
    }

    private void start(String name) {
        if (running.containsKey(name)) return;
        final Plugin p = plugins.get(name);
        final PluginContext ctx = new PluginContext(name, host, granted(name), store);
        final JSONObject cfg = config(name);
        Runner r = new Runner(p, ctx);
        running.put(name, r);
        lastStatus.remove(name);
        r.post(new Task() {
            @Override
            public void run() throws Exception {
                p.start(ctx, cfg);
            }
        }, false);
        r.thread.start();
        host.log("plugins: started " + name + " with " + ctx.granted());
    }

    private void stop(String name, String why) {
        Runner r = running.remove(name);
        if (r == null) return;
        r.ctx.close();
        r.finish();
        try {
            r.plugin.stop();
        } catch (Throwable t) {
            host.log("plugins: " + name + " failed to stop: " + t);
        }
        lastStatus.put(name, why);
        host.log("plugins: " + name + " " + why);
    }

    /** A plugin threw MAX_FAILURES times in a row. */
    private synchronized void failed(Runner r, Throwable t) {
        if (running.get(r.plugin.name()) != r) return;
        stop(r.plugin.name(), "failed: " + t);
    }

    // ---- events -------------------------------------------------------------------------------

    /** Hands an event to every running plugin that has the "status" permission. */
    void event(final String type, final JSONObject data) {
        List<Runner> rs;
        synchronized (this) {
            rs = new ArrayList<>(running.values());
        }
        for (final Runner r : rs) {
            if (!r.ctx.has(PluginPermissions.STATUS)) continue;
            if (Plugin.EVENT_STATE.equals(type)) {
                // Merged: a plugin that falls behind gets the latest state once, not every step in between.
                if (!r.statePending.compareAndSet(false, true)) continue;
                r.post(new Task() {
                    @Override
                    public void run() throws Exception {
                        r.statePending.set(false);
                        r.plugin.onEvent(type, host.state());
                    }
                }, true);
            } else {
                r.post(new Task() {
                    @Override
                    public void run() throws Exception {
                        r.plugin.onEvent(type, data);
                    }
                }, true);
            }
        }
    }

    /** Waits until every running plugin has handled what was queued (for tests). */
    boolean awaitIdle(long timeoutMs) throws InterruptedException {
        List<Runner> rs;
        synchronized (this) {
            rs = new ArrayList<>(running.values());
        }
        long until = System.currentTimeMillis() + timeoutMs;
        for (Runner r : rs) {
            final CountDownLatch done = new CountDownLatch(1);
            r.post(new Probe() {
                @Override
                public void run() {
                    done.countDown();
                }
            }, false);
            if (!done.await(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) return false;
        }
        return true;
    }

    // ---- the agent's API -------------------------------------------------------------------------

    /** One line per plugin, for R2 Main's status. */
    synchronized String status() {
        StringBuilder b = new StringBuilder("plugins:");
        for (String name : plugins.keySet()) b.append(' ').append(name).append(" (").append(stateOf(name)).append(')');
        return b.toString();
    }

    private String stateOf(String name) {
        Runner r = running.get(name);
        if (r != null) {
            String s;
            try {
                s = r.plugin.status();
            } catch (Throwable t) {
                s = "status failed: " + t;
            }
            return "running" + (s == null || s.isEmpty() ? "" : ", " + s);
        }
        String last = lastStatus.get(name);
        return enabled(name) ? (last == null ? "enabled, not started" : last) : "disabled";
    }

    /** {"op":"list"} and the others; returns the reply (without agent and seq). */
    synchronized JSONObject request(JSONObject msg) {
        JSONObject reply = new JSONObject();
        try {
            String op = msg.optString("op"), name = msg.optString("name");
            if (!"list".equals(op) && !plugins.containsKey(name)) {
                return reply.put("ok", false).put("error", name.isEmpty() ? "name= is required" : "no plugin named " + name);
            }
            switch (op) {
                case "list": {
                    JSONArray list = new JSONArray();
                    for (String n : plugins.keySet()) list.put(describe(n));
                    JSONObject perms = new JSONObject();
                    for (Map.Entry<String, String> e : PluginPermissions.ALL.entrySet()) perms.put(e.getKey(), e.getValue());
                    return reply.put("ok", true).put("plugins", list).put("permissions", perms);
                }
                case "enable":
                case "disable": {
                    boolean on = "enable".equals(op);
                    entry(name).put("enabled", on);
                    persist();
                    if (on) {
                        stop(name, "restarting");
                        start(name);
                    } else {
                        stop(name, "disabled");
                    }
                    return reply.put("ok", true).put("result", name + (on ? " enabled" : " disabled")).put("plugin", describe(name));
                }
                case "grant": {
                    Set<String> wanted = PluginPermissions.parse(msg.optString("permissions"));
                    Set<String> asked = plugins.get(name).permissions();
                    Set<String> extra = new TreeSet<>(wanted);
                    extra.removeAll(asked);
                    if (!extra.isEmpty()) {
                        return reply.put("ok", false).put("error", name + " doesn't ask for " + extra + "; it asks for " + new TreeSet<>(asked));
                    }
                    entry(name).put("granted", new JSONArray(wanted));
                    persist();
                    if (running.containsKey(name)) {
                        stop(name, "restarting");
                        start(name);
                    }
                    return reply.put("ok", true).put("result", name + " may now: " + (wanted.isEmpty() ? "nothing" : wanted)).put("plugin", describe(name));
                }
                case "config": {
                    // Merges the given keys into the stored config; a null value removes a key.
                    JSONObject change = msg.optJSONObject("config");
                    if (change == null) return reply.put("ok", false).put("error", "send the config as a JSON object");
                    final JSONObject cfg = config(name);
                    for (Iterator<String> it = change.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (change.isNull(k)) cfg.remove(k);
                        else cfg.put(k, change.get(k));
                    }
                    entry(name).put("config", cfg);
                    persist();
                    final Runner r = running.get(name);
                    if (r != null) {
                        final JSONObject copy = new JSONObject(cfg.toString());
                        r.post(new Task() {
                            @Override
                            public void run() throws Exception {
                                r.plugin.configure(copy);
                            }
                        }, false);
                    }
                    return reply.put("ok", true).put("result", "config of " + name + " saved").put("plugin", describe(name));
                }
                default:
                    return reply.put("ok", false).put("error", "unknown op " + op);
            }
        } catch (IllegalArgumentException e) {
            return error(reply, e.getMessage());
        } catch (Exception e) {
            host.log("plugins: request failed: " + e);
            return error(reply, String.valueOf(e));
        }
    }

    private static JSONObject error(JSONObject reply, String msg) {
        try {
            return reply.put("ok", false).put("error", msg);
        } catch (Exception impossible) {
            return reply;
        }
    }

    private JSONObject describe(String name) throws Exception {
        Plugin p = plugins.get(name);
        return new JSONObject().put("name", name).put("description", p.description())
                .put("enabled", enabled(name)).put("running", running.containsKey(name)).put("state", stateOf(name))
                .put("asks_for", new JSONArray(new TreeSet<>(p.permissions()))).put("granted", new JSONArray(granted(name)))
                .put("config", masked(config(name)));
    }

    /** The config with values of keys like "password" or "token" hidden. */
    static JSONObject masked(JSONObject c) throws Exception {
        JSONObject m = new JSONObject();
        for (Iterator<String> it = c.keys(); it.hasNext(); ) {
            String k = it.next();
            boolean secret = false;
            for (String w : SECRET_WORDS) secret |= k.toLowerCase(java.util.Locale.US).contains(w);
            m.put(k, secret ? "(set)" : c.get(k));
        }
        return m;
    }

    synchronized List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.keySet()));
    }

    // ---- one thread per running plugin ------------------------------------------------------------

    private interface Task {
        void run() throws Exception;
    }

    /** A task that is not the plugin's own work (it doesn't reset the failure count). */
    private abstract static class Probe implements Task {
    }

    private final class Runner implements Runnable {
        final Plugin plugin;
        final PluginContext ctx;
        final Thread thread;
        final AtomicBoolean statePending = new AtomicBoolean();
        private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
        private volatile boolean done;
        private int failures;
        private boolean started;
        private int dropped;

        Runner(Plugin plugin, PluginContext ctx) {
            this.plugin = plugin;
            this.ctx = ctx;
            this.thread = new Thread(this, "plugin-" + plugin.name());
            thread.setDaemon(true);
        }

        /** Queues a task; events (droppable) are dropped once the queue is full. */
        void post(Task t, boolean droppable) {
            if (done) return;
            if (droppable && queue.size() >= QUEUE_LIMIT) {
                if (dropped++ % 50 == 0) host.log("plugins: " + plugin.name() + " is falling behind, events dropped: " + dropped);
                return;
            }
            queue.offer(t);
        }

        void finish() {
            done = true;
            thread.interrupt();
        }

        @Override
        public void run() {
            while (!done) {
                Task t;
                try {
                    t = queue.take();
                } catch (InterruptedException e) {
                    break;
                }
                if (done) break;
                try {
                    t.run();
                    if (!(t instanceof Probe)) {
                        failures = 0;
                        started = true;
                    }
                } catch (InterruptedException e) {
                    if (done) break;
                } catch (Throwable e) {
                    host.log("plugins: " + plugin.name() + ": " + e);
                    if (!started || ++failures >= MAX_FAILURES) { // the first task is start(): failing it stops the plugin
                        failed(this, e);
                        break;
                    }
                }
            }
        }
    }
}
