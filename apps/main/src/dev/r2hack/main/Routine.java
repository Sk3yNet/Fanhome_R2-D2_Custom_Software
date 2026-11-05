package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A routine: a named list of steps, stored as JSON (format: docs/routines.md). Parsing checks
 * everything up front, so a routine that loads is one the interpreter (RoutineRunner) can run.
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class Routine {
    static final int MAX_STEPS = 500;          // steps in one routine, counting nested ones once
    static final int MAX_DEPTH = 4;            // nesting of repeat blocks
    static final int MAX_REPEAT = 1000;
    static final double MAX_WAIT_S = 600;
    static final double DEFAULT_TIMEOUT_S = 120;
    static final double MAX_TIMEOUT_S = 900;
    static final double DEFAULT_WAIT_UNTIL_S = 30;
    static final int HEAD_LIMIT = 130;          // same cap as the agent's motion guard
    /** Controller commands a routine may not send: they power the robot off or reset the board. */
    static final String[] FORBIDDEN_CMDS = {"shut-down", "ready", "reset", "update", "upgrade"};
    /** gin fields a wait_until can test, as Robot keeps them. */
    static final String[] FIELDS = {"battery", "charging", "arm", "lightsaber", "projector", "lcd_s", "lcd_l"};
    static final String[] OPS = {"eq", "ne", "lt", "le", "gt", "ge"};

    /**
     * Built-in actions (Brain) that an "action" step can run, and that a stored routine of the same
     * name replaces. Brain's other actions (stop, settings, pairing, patrol) are reserved names.
     */
    static final Set<String> STEP_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "wake", "turn-left", "turn-right", "turn-around", "forward", "circle", "dance", "shake-head",
            "who-are-you", "noise", "not-recognized", "lightsaber", "arm", "short-lcd", "long-lcd",
            "flash-front", "flash-back", "leia", "luke")));

    enum Kind { WAIT, SOUND, HEAD, MODE, DRIVE, LED, CMD, ACTION, WAIT_UNTIL, REPEAT, ROUTINE }

    static final class Step {
        Kind kind;
        long ms;                 // WAIT, DRIVE, WAIT_UNTIL (timeout)
        int number;              // SOUND id, HEAD angle, MODE, REPEAT count
        boolean flag;            // SOUND: wait for it to end; WAIT_UNTIL: carry on after a timeout
        int power, angle;        // DRIVE
        JSONObject json;         // LED values, CMD command
        String name;             // ACTION, ROUTINE, WAIT_UNTIL field
        String op;               // WAIT_UNTIL
        int value;               // WAIT_UNTIL
        List<Step> steps;        // REPEAT
    }

    final String name;
    final String description;
    final long timeoutMs;
    /** Runs while R2 is on the charger (sounds, lights, head); the controller ignores driving then anyway. */
    final boolean whileCharging;
    final List<Step> steps;
    final JSONObject source;

    private Routine(String name, String description, long timeoutMs, boolean whileCharging, List<Step> steps, JSONObject source) {
        this.name = name;
        this.description = description;
        this.timeoutMs = timeoutMs;
        this.whileCharging = whileCharging;
        this.steps = Collections.unmodifiableList(steps);
        this.source = source;
    }

    /** A routine built in code (e.g. a trip to a place, Places.trip), not parsed from JSON. */
    static Routine built(String name, String description, long timeoutMs, List<Step> steps, JSONObject source) {
        return new Routine(name, description, timeoutMs, false, new ArrayList<>(steps), source);
    }

    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    static boolean validName(String n) {
        return n != null && n.matches("[a-z0-9][a-z0-9-]{0,31}");
    }

    /**
     * Parses and checks a routine. actions: the built-in actions an "action" step may use.
     * Throws Invalid with a message that says which step is wrong.
     */
    static Routine parse(JSONObject j, Set<String> actions) throws Invalid {
        String name = j.optString("name", "");
        if (!validName(name)) throw new Invalid("name must be 1-32 characters: a-z, 0-9 and -, starting with a letter or digit");
        for (Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("name") && !k.equals("description") && !k.equals("timeout") && !k.equals("steps")
                    && !k.equals("while_charging")) {
                throw new Invalid("unknown key \"" + k + "\" (allowed: name, description, timeout, while_charging, steps)");
            }
        }
        double timeout = number(j, "timeout", DEFAULT_TIMEOUT_S, 1, MAX_TIMEOUT_S, "timeout");
        JSONArray arr = j.optJSONArray("steps");
        if (arr == null) throw new Invalid("steps must be a list");
        int[] count = {0};
        List<Step> steps = parseSteps(arr, actions, 1, "steps", count);
        if (steps.isEmpty()) throw new Invalid("steps is empty");
        return new Routine(name, j.optString("description", ""), (long) (timeout * 1000),
                j.optBoolean("while_charging", false), steps, j);
    }

    private static List<Step> parseSteps(JSONArray arr, Set<String> actions, int depth, String where, int[] count) throws Invalid {
        if (depth > MAX_DEPTH) throw new Invalid(where + ": repeat blocks nest more than " + MAX_DEPTH + " deep");
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String at = where + "[" + i + "]";
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new Invalid(at + ": each step is an object, e.g. {\"wait\": 1}");
            if (++count[0] > MAX_STEPS) throw new Invalid("more than " + MAX_STEPS + " steps");
            out.add(parseStep(o, actions, depth, at, count));
        }
        return out;
    }

    private static Step parseStep(JSONObject o, Set<String> actions, int depth, String at, int[] count) throws Invalid {
        Step s = new Step();
        String kind = null;
        for (Kind k : Kind.values()) {
            String key = k.name().toLowerCase();
            if (o.has(key)) {
                if (kind != null) throw new Invalid(at + ": has both \"" + kind + "\" and \"" + key + "\"; use one per step");
                kind = key;
                s.kind = k;
            }
        }
        if (kind == null) throw new Invalid(at + ": unknown step " + o + " (see docs/routines.md)");
        switch (s.kind) {
            case WAIT:
                allowOnly(o, at, "wait");
                s.ms = ms(number(o, "wait", 0, 0, MAX_WAIT_S, at + ".wait"));
                break;
            case SOUND:
                allowOnly(o, at, "sound", "until_done");
                s.number = integer(o, "sound", 0, 255, at + ".sound");
                s.flag = o.optBoolean("until_done", false);
                break;
            case HEAD:
                allowOnly(o, at, "head");
                s.number = integer(o, "head", -HEAD_LIMIT, HEAD_LIMIT, at + ".head");
                break;
            case MODE:
                allowOnly(o, at, "mode");
                s.number = integer(o, "mode", 0, 20, at + ".mode");
                break;
            case DRIVE: {
                allowOnly(o, at, "drive");
                JSONObject d = o.optJSONObject("drive");
                if (d == null) throw new Invalid(at + ".drive: {\"power\": 0-100, \"angle\": -180 to 359, \"seconds\": n}");
                allowOnly(d, at + ".drive", "power", "angle", "seconds");
                s.power = integer(d, "power", 0, 100, at + ".drive.power");
                // -90 is the left spin; 270 is not (the controller backs up instead), so negative angles are allowed.
                s.angle = d.has("angle") ? integer(d, "angle", -180, 359, at + ".drive.angle") : 0;
                s.ms = ms(number(d, "seconds", -1, 0.1, 60, at + ".drive.seconds"));
                break;
            }
            case LED: {
                allowOnly(o, at, "led");
                JSONObject l = o.optJSONObject("led");
                if (l == null || l.length() == 0) throw new Invalid(at + ".led: e.g. {\"r\": 2, \"b\": 2} (0-6 each)");
                allowOnly(l, at + ".led", "r", "b", "y", "g");
                JSONObject cmd = new JSONObject();
                try {
                    cmd.put("cmd", "led");
                    for (String c : new String[] {"r", "b", "y", "g"}) {
                        if (l.has(c)) cmd.put(c, integer(l, c, 0, 6, at + ".led." + c));
                    }
                } catch (Invalid e) {
                    throw e;
                } catch (Exception e) {
                    throw new Invalid(at + ".led: " + e.getMessage());
                }
                s.json = cmd;
                break;
            }
            case CMD: {
                allowOnly(o, at, "cmd");
                JSONObject c = o.optJSONObject("cmd");
                if (c == null || c.optString("cmd", "").isEmpty()) {
                    throw new Invalid(at + ".cmd: a controller command, e.g. {\"cmd\": \"lcd\", \"s\": 2, \"l\": 1}");
                }
                for (String f : FORBIDDEN_CMDS) {
                    if (f.equals(c.optString("cmd"))) throw new Invalid(at + ".cmd: \"" + f + "\" is not allowed in a routine");
                }
                s.json = c;
                break;
            }
            case ACTION:
                allowOnly(o, at, "action");
                s.name = o.optString("action", "");
                if (!actions.contains(s.name)) {
                    throw new Invalid(at + ".action: \"" + s.name + "\" is not a built-in action that a routine can run " + new java.util.TreeSet<>(actions));
                }
                break;
            case WAIT_UNTIL: {
                allowOnly(o, at, "wait_until", "timeout", "else");
                JSONObject c = o.optJSONObject("wait_until");
                if (c == null) throw new Invalid(at + ".wait_until: e.g. {\"field\": \"arm\", \"eq\": 1}");
                s.name = c.optString("field", "");
                if (!contains(FIELDS, s.name)) throw new Invalid(at + ".wait_until.field: one of " + join(FIELDS));
                for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.equals("field")) continue;
                    if (!contains(OPS, k)) throw new Invalid(at + ".wait_until: unknown \"" + k + "\" (use field plus one of " + join(OPS) + ")");
                    if (s.op != null) throw new Invalid(at + ".wait_until: one comparison per step");
                    s.op = k;
                    s.value = integer(c, k, -1000, 1000, at + ".wait_until." + k);
                }
                if (s.op == null) throw new Invalid(at + ".wait_until: needs a comparison, e.g. \"eq\": 1");
                s.ms = ms(number(o, "timeout", DEFAULT_WAIT_UNTIL_S, 0.1, MAX_WAIT_S, at + ".timeout"));
                String e = o.optString("else", "stop");
                if (!e.equals("stop") && !e.equals("continue")) throw new Invalid(at + ".else: \"stop\" or \"continue\"");
                s.flag = e.equals("continue");
                break;
            }
            case REPEAT: {
                allowOnly(o, at, "repeat", "steps");
                s.number = integer(o, "repeat", 1, MAX_REPEAT, at + ".repeat");
                JSONArray inner = o.optJSONArray("steps");
                if (inner == null || inner.length() == 0) throw new Invalid(at + ": repeat needs a non-empty steps list");
                s.steps = parseSteps(inner, actions, depth + 1, at + ".steps", count);
                break;
            }
            case ROUTINE:
                allowOnly(o, at, "routine");
                s.name = o.optString("routine", "");
                if (!validName(s.name)) throw new Invalid(at + ".routine: not a valid routine name");
                break;
            default:
                throw new Invalid(at + ": unknown step");
        }
        return s;
    }

    /** Number of steps, with nested ones counted once (for listings). */
    int stepCount() {
        return count(steps);
    }

    private static int count(List<Step> list) {
        int n = 0;
        for (Step s : list) n += 1 + (s.steps != null ? count(s.steps) : 0);
        return n;
    }

    private static void allowOnly(JSONObject o, String at, String... keys) throws Invalid {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!contains(keys, k)) throw new Invalid(at + ": unknown key \"" + k + "\" (allowed: " + join(keys) + ")");
        }
    }

    /** A number from o[key], or def if missing (def < 0: required), within lo..hi. */
    private static double number(JSONObject o, String key, double def, double lo, double hi, String at) throws Invalid {
        if (!o.has(key)) {
            if (def < 0) throw new Invalid(at + " is required");
            return def;
        }
        Object v = o.opt(key);
        if (!(v instanceof Number)) throw new Invalid(at + ": a number is needed, not " + v);
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || d < lo || d > hi) throw new Invalid(at + ": " + d + " is outside " + fmt(lo) + " to " + fmt(hi));
        return d;
    }

    private static int integer(JSONObject o, String key, int lo, int hi, String at) throws Invalid {
        if (!o.has(key)) throw new Invalid(at + " is required");
        Object v = o.opt(key);
        if (!(v instanceof Number)) throw new Invalid(at + ": a whole number is needed, not " + v);
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d)) throw new Invalid(at + ": a whole number is needed, not " + v);
        if (d < lo || d > hi) throw new Invalid(at + ": " + (long) d + " is outside " + lo + " to " + hi);
        return (int) d;
    }

    private static long ms(double seconds) {
        return Math.round(seconds * 1000);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    static boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equals(s)) return true;
        return false;
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String a : arr) sb.append(sb.length() > 0 ? ", " : "").append(a);
        return sb.toString();
    }
}
