package dev.r2hack.main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * Routines saved as files/routines/<name>.json in R2 Main's data, so they survive updates.
 * Every file is checked again when it is read; a bad file is listed with its error and doesn't run.
 */
final class RoutineStore {
    static final int MAX_ROUTINES = 200;
    static final int MAX_BYTES = 60 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private final Set<String> actions;
    private volatile Triggers.Table triggers;   // built on first use, rebuilt after each save or delete
    /** Called after the routines (and so maybe the triggers) changed, e.g. so voice reloads its phrases. */
    volatile Runnable onChange;

    /** actions: the built-in actions a routine's "action" step may use. */
    RoutineStore(File dir, Set<String> actions) {
        this.dir = dir;
        this.actions = actions;
    }

    /** Checks and saves a routine, replacing one with the same name. */
    synchronized Routine save(JSONObject json) throws Routine.Invalid {
        Routine r = Routine.parse(json, actions);
        String clash = triggers().conflict(r.name, r.triggers);
        if (clash != null) throw new Routine.Invalid("triggers: " + clash);
        String text = json.toString();
        if (text.getBytes(UTF8).length > MAX_BYTES) throw new Routine.Invalid("routine is larger than " + MAX_BYTES / 1024 + " KB");
        File f = file(r.name);
        if (!f.exists() && names().size() >= MAX_ROUTINES) throw new Routine.Invalid("already " + MAX_ROUTINES + " routines; delete some first");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Routine.Invalid("can't create " + dir);
        File tmp = new File(dir, r.name + ".json.tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes(UTF8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) throw new Exception("rename failed");
        } catch (Exception e) {
            tmp.delete();
            throw new Routine.Invalid("could not save: " + e.getMessage());
        }
        changed();
        return r;
    }

    synchronized boolean delete(String name) {
        boolean ok = Routine.validName(name) && file(name).delete();
        if (ok) changed();
        return ok;
    }

    /** All valid routines' triggers. A routine whose file no longer passes the checks has none. */
    synchronized Triggers.Table triggers() {
        Triggers.Table t = triggers;
        if (t != null) return t;
        java.util.Map<String, List<Triggers.Trigger>> all = new java.util.LinkedHashMap<>();
        for (String n : names()) {
            try {
                Routine r = load(n);
                if (r != null && !r.triggers.isEmpty()) all.put(n, r.triggers);
            } catch (Routine.Invalid ignored) {
            }
        }
        triggers = t = new Triggers.Table(all);
        return t;
    }

    private void changed() {
        triggers = null;
        Runnable r = onChange;
        if (r != null) r.run();
    }

    synchronized boolean exists(String name) {
        return Routine.validName(name) && file(name).isFile();
    }

    /** The routine, or null if there is none by that name. Throws Invalid if the file is bad. */
    synchronized Routine load(String name) throws Routine.Invalid {
        if (!exists(name)) return null;
        JSONObject j;
        try {
            j = new JSONObject(read(file(name)));
        } catch (Exception e) {
            throw new Routine.Invalid("not valid JSON: " + e.getMessage());
        }
        Routine r = Routine.parse(j, actions);
        if (!r.name.equals(name)) throw new Routine.Invalid("file " + name + ".json holds routine \"" + r.name + "\"");
        return r;
    }

    /** Names of the stored routines, sorted. */
    synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        String[] files = dir.list();
        if (files == null) return out;
        for (String f : files) {
            if (!f.endsWith(".json")) continue;
            String n = f.substring(0, f.length() - 5);
            if (Routine.validName(n)) out.add(n);
        }
        Collections.sort(out);
        return out;
    }

    private File file(String name) {
        return new File(dir, name + ".json");
    }

    static String read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_BYTES * 2) throw new Exception("file too large");
            }
            return new String(b.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
