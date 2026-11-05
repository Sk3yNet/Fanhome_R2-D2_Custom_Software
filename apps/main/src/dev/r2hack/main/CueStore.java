package dev.r2hack.main;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * Watch-with-me cue files, saved as files/cues/<name>.json in R2 Main's data (like RoutineStore).
 * Every file is checked again when it is read; a bad file is listed with its error and isn't used.
 */
final class CueStore {
    static final int MAX_FILES = 200;
    static final int MAX_BYTES = 60 * 1024;   // the agent takes request bodies up to 64 KB
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private final Set<String> actions;

    CueStore(File dir, Set<String> actions) {
        this.dir = dir;
        this.actions = actions;
    }

    /** Checks and saves a cue file, replacing one with the same name. */
    synchronized CueFile save(JSONObject json) throws Routine.Invalid {
        CueFile c = CueFile.parse(json, actions);
        String text = json.toString();
        if (text.getBytes(UTF8).length > MAX_BYTES) throw new Routine.Invalid("cue file is larger than " + MAX_BYTES / 1024 + " KB");
        File f = file(c.name);
        if (!f.exists() && names().size() >= MAX_FILES) throw new Routine.Invalid("already " + MAX_FILES + " cue files; delete some first");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Routine.Invalid("can't create " + dir);
        File tmp = new File(dir, c.name + ".json.tmp");
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
        return c;
    }

    synchronized boolean delete(String name) {
        return Routine.validName(name) && file(name).delete();
    }

    synchronized boolean exists(String name) {
        return Routine.validName(name) && file(name).isFile();
    }

    /** The cue file, or null if there is none by that name. Throws Invalid if the file is bad. */
    synchronized CueFile load(String name) throws Routine.Invalid {
        if (!exists(name)) return null;
        JSONObject j;
        try {
            j = new JSONObject(RoutineStore.read(file(name)));
        } catch (Exception e) {
            throw new Routine.Invalid("not valid JSON: " + e.getMessage());
        }
        CueFile c = CueFile.parse(j, actions);
        if (!c.name.equals(name)) throw new Routine.Invalid("file " + name + ".json holds cue file \"" + c.name + "\"");
        return c;
    }

    /** Names of the stored cue files, sorted. */
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

    /** The first valid cue file (by name) whose match list fits the media title, or null. */
    synchronized CueFile find(String mediaTitle) {
        for (String n : names()) {
            try {
                CueFile c = load(n);
                if (c != null && c.matches(mediaTitle)) return c;
            } catch (Routine.Invalid ignored) {
                // listed with its error by GET /cues
            }
        }
        return null;
    }

    private File file(String name) {
        return new File(dir, name + ".json");
    }
}
