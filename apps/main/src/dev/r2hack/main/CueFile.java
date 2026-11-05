package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A watch-with-me cue file: the reactions R2 has for one film or episode, each at a time in it
 * (format: docs/watch-with-me.md). A reaction is a stored routine or a short list of routine steps.
 * Reactions never drive: steps that would are refused here, and WatchWithMe runs stored routines
 * stationary (RoutineRunner.stationary()), so R2 stays where he is while you watch.
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class CueFile {
    static final int MAX_CUES = 2000;
    static final double MAX_AT_S = 12 * 3600;      // 12 hours
    static final double MAX_OFFSET_S = 600;
    static final double DEFAULT_REACTION_S = 30;   // a reaction's timeout
    static final double MAX_REACTION_S = 120;
    static final int MAX_MATCH = 20;

    static final class Cue {
        final double at;           // seconds into the film, before the file's offset
        final String routine;      // a stored routine, or null
        final Routine steps;       // inline steps, or null
        final String note;

        Cue(double at, String routine, Routine steps, String note) {
            this.at = at;
            this.routine = routine;
            this.steps = steps;
            this.note = note;
        }

        /** What runs, for logs and status: the routine's name or "N steps". */
        String what() {
            return routine != null ? routine : steps.stepCount() + " steps";
        }
    }

    final String name;
    final String title;
    final String description;
    /** Lower-case parts of a media title that pick this file, e.g. "a new hope". */
    final List<String> match;
    /** Seconds added to every cue time, to line the file up with a different cut or a slow player. */
    final double offset;
    /** Sorted by time. */
    final List<Cue> cues;
    final JSONObject source;

    private CueFile(String name, String title, String description, List<String> match, double offset,
                    List<Cue> cues, JSONObject source) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.match = Collections.unmodifiableList(match);
        this.offset = offset;
        this.cues = Collections.unmodifiableList(cues);
        this.source = source;
    }

    /** When a cue fires, in seconds of the player's position. */
    double time(Cue c) {
        return c.at + offset;
    }

    /** Whether a media title (as Home Assistant reports it) picks this file. */
    boolean matches(String mediaTitle) {
        if (mediaTitle == null || mediaTitle.trim().isEmpty()) return false;
        String t = mediaTitle.toLowerCase(Locale.US);
        for (String m : match) if (t.contains(m)) return true;
        return false;
    }

    /**
     * Parses and checks a cue file. actions: the built-in actions a step may run.
     * Throws Routine.Invalid with a message that names the cue, e.g. cues[3].steps[0].head: ...
     */
    static CueFile parse(JSONObject j, Set<String> actions) throws Routine.Invalid {
        String name = j.optString("name", "");
        if (!Routine.validName(name)) throw new Routine.Invalid("name must be 1-32 characters: a-z, 0-9 and -, starting with a letter or digit");
        allowOnly(j, "", "name", "title", "description", "match", "offset", "cues");
        List<String> match = new ArrayList<>();
        if (j.has("match")) {
            JSONArray m = j.optJSONArray("match");
            if (m == null) throw new Routine.Invalid("match must be a list of title parts, e.g. [\"a new hope\"]");
            if (m.length() > MAX_MATCH) throw new Routine.Invalid("match: at most " + MAX_MATCH + " entries");
            for (int i = 0; i < m.length(); i++) {
                Object v = m.opt(i);
                if (!(v instanceof String) || ((String) v).trim().length() < 3) {
                    throw new Routine.Invalid("match[" + i + "]: a piece of the title, at least 3 characters");
                }
                match.add(((String) v).trim().toLowerCase(Locale.US));
            }
        }
        double offset = 0;
        if (j.has("offset")) {
            Object v = j.opt("offset");
            if (!(v instanceof Number)) throw new Routine.Invalid("offset: a number of seconds is needed, not " + v);
            offset = ((Number) v).doubleValue();
            if (Double.isNaN(offset) || Math.abs(offset) > MAX_OFFSET_S) {
                throw new Routine.Invalid("offset: " + offset + " is outside -" + (int) MAX_OFFSET_S + " to " + (int) MAX_OFFSET_S);
            }
        }
        JSONArray arr = j.optJSONArray("cues");
        if (arr == null || arr.length() == 0) throw new Routine.Invalid("cues must be a non-empty list");
        if (arr.length() > MAX_CUES) throw new Routine.Invalid("more than " + MAX_CUES + " cues");
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String at = "cues[" + i + "]";
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new Routine.Invalid(at + ": each cue is an object, e.g. {\"at\": \"0:42:10\", \"routine\": \"gasp\"}");
            cues.add(parseCue(o, actions, at));
        }
        Collections.sort(cues, new Comparator<Cue>() {
            @Override
            public int compare(Cue a, Cue b) {
                return Double.compare(a.at, b.at);
            }
        });
        return new CueFile(name, j.optString("title", ""), j.optString("description", ""), match, offset, cues, j);
    }

    private static Cue parseCue(JSONObject o, Set<String> actions, String at) throws Routine.Invalid {
        allowOnly(o, at, "at", "routine", "steps", "note", "timeout");
        if (!o.has("at")) throw new Routine.Invalid(at + ".at is required: seconds, or a time like \"1:02:03\"");
        double t = time(o.opt("at"), at + ".at");
        boolean hasRoutine = o.has("routine"), hasSteps = o.has("steps");
        if (hasRoutine == hasSteps) throw new Routine.Invalid(at + ": needs either \"routine\" or \"steps\"");
        String note = o.optString("note", "");
        if (hasRoutine) {
            if (o.has("timeout")) throw new Routine.Invalid(at + ".timeout: only for steps (a routine has its own)");
            String r = o.optString("routine", "");
            if (!Routine.validName(r)) throw new Routine.Invalid(at + ".routine: not a valid routine name");
            return new Cue(t, r, null, note);
        }
        JSONArray steps = o.optJSONArray("steps");
        if (steps == null) throw new Routine.Invalid(at + ".steps must be a list");
        Object timeout = o.has("timeout") ? o.opt("timeout") : DEFAULT_REACTION_S;
        if (!(timeout instanceof Number) || ((Number) timeout).doubleValue() < 1 || ((Number) timeout).doubleValue() > MAX_REACTION_S) {
            throw new Routine.Invalid(at + ".timeout: 1 to " + (int) MAX_REACTION_S + " seconds");
        }
        Routine r;
        try {
            JSONObject wrap = new JSONObject().put("name", "cue").put("timeout", timeout)
                    .put("while_charging", true).put("steps", steps);
            r = Routine.parse(wrap, actions);
        } catch (Routine.Invalid e) {
            throw new Routine.Invalid(at + "." + e.getMessage());
        } catch (Exception e) {
            throw new Routine.Invalid(at + ": " + e.getMessage());
        }
        refuseDriving(r.steps, at + ".steps");
        return new Cue(t, null, r, note);
    }

    private static void refuseDriving(List<Routine.Step> steps, String where) throws Routine.Invalid {
        for (int i = 0; i < steps.size(); i++) {
            Routine.Step s = steps.get(i);
            if (RoutineRunner.moves(s)) {
                throw new Routine.Invalid(where + "[" + i + "]: no driving in a watch-with-me cue (R2 stays put while you watch)");
            }
            if (s.steps != null) refuseDriving(s.steps, where + "[" + i + "].steps");
        }
    }

    /** Seconds from a number or a time like "1:02:03", "2:03" or "2:03.5". */
    static double time(Object v, String at) throws Routine.Invalid {
        double t;
        if (v instanceof Number) {
            t = ((Number) v).doubleValue();
        } else if (v instanceof String && ((String) v).trim().matches("(\\d+:){0,2}\\d+(\\.\\d+)?")) {
            String[] parts = ((String) v).trim().split(":");
            t = 0;
            for (int i = 0; i < parts.length; i++) {
                double p = Double.parseDouble(parts[i]);
                if (i > 0 && p >= 60) throw new Routine.Invalid(at + ": \"" + v + "\" has minutes or seconds of 60 or more");
                t = t * 60 + p;
            }
        } else {
            throw new Routine.Invalid(at + ": seconds, or a time like \"1:02:03\", not " + v);
        }
        if (Double.isNaN(t) || t < 0 || t > MAX_AT_S) throw new Routine.Invalid(at + ": " + v + " is outside 0 to 12 hours");
        return t;
    }

    /** "1:02:03" or "2:03" for a number of seconds (tenths kept when there are any). */
    static String clock(double seconds) {
        if (Double.isNaN(seconds)) return "?";
        boolean neg = seconds < 0;
        long tenths = Math.round(Math.abs(seconds) * 10);
        long s = tenths / 10, h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        String out = h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
        if (tenths % 10 != 0) out += "." + tenths % 10;
        return neg ? "-" + out : out;
    }

    private static void allowOnly(JSONObject o, String at, String... keys) throws Routine.Invalid {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!Routine.contains(keys, k)) {
                StringBuilder sb = new StringBuilder();
                for (String a : keys) sb.append(sb.length() > 0 ? ", " : "").append(a);
                throw new Routine.Invalid((at.isEmpty() ? "" : at + ": ") + "unknown key \"" + k + "\" (allowed: " + sb + ")");
            }
        }
    }
}
