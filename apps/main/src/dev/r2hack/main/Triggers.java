package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What starts a routine besides an explicit run: a robot button, a voice phrase, a schedule, a named
 * event (from Home Assistant or POST /event) or a message on any MQTT topic. Triggers are part of the
 * routine's JSON ("triggers": [...], format in docs/routines.md). Triggers.Table holds all stored
 * routines' triggers and answers "which routine does this button / phrase / event / message start?".
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class Triggers {
    /** Buttons a routine may take over: lightsaber, arm, patrol. Power, Wi-Fi and pairing stay built in. */
    static final int[] BUTTONS = {4, 5, 6};
    static final int MAX_TRIGGERS = 20;          // per routine
    static final int MIN_EVERY_MIN = 5, MAX_EVERY_MIN = 1440;
    static final String[] DAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"}; // Calendar.SUNDAY = 1
    /** Phrases built into voice commands (Voice.PHRASES), which a routine phrase may not reuse. */
    static final String[] BUILT_IN_PHRASES = {
            "hey r two d two", "turn left", "turn right", "turn around", "go forward", "shake your head",
            "walk a circle", "dance please", "make some noise", "who are you", "lightsaber", "move arms",
            "patrol", "stop", "princess leia", "skywalker",
            "go to", "go home", "come home", // places (Places.phrases)
    };

    enum Kind { BUTTON, VOICE, SCHEDULE, EVENT, MQTT }

    static final class Trigger {
        Kind kind;
        int button;              // BUTTON
        String text;             // VOICE phrase, EVENT name, MQTT topic filter
        // SCHEDULE: either at (minute of the day) or every (minutes), optionally within from..to.
        int at = -1, every, from = -1, to = -1;
        int days = 0x7f;         // bit per Calendar day - 1 (bit 0 Sunday)
        // MQTT: what the message must be. Neither set: any message.
        String payload;          // the whole payload, trimmed
        String field;            // a top-level key of a JSON payload...
        Object value;            // ...and the value it must have (compared as text)
        boolean everyMessage;    // MQTT: fire on each matching message, not only when it starts matching
        /** Automatic triggers (schedule, event, mqtt) skip by default if something is running; buttons and voice replace it. */
        boolean replace;
    }

    static final class Hit {
        final String routine;
        final boolean replace;

        Hit(String routine, boolean replace) {
            this.routine = routine;
            this.replace = replace;
        }

        @Override
        public String toString() {
            return routine + (replace ? "" : " (if idle)");
        }
    }

    private Triggers() {
    }

    // ---- parsing ------------------------------------------------------------------------

    static List<Trigger> parse(JSONArray arr) throws Routine.Invalid {
        List<Trigger> out = new ArrayList<>();
        if (arr == null) return out;
        if (arr.length() > MAX_TRIGGERS) throw new Routine.Invalid("triggers: at most " + MAX_TRIGGERS);
        for (int i = 0; i < arr.length(); i++) {
            String at = "triggers[" + i + "]";
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new Routine.Invalid(at + ": each trigger is an object, e.g. {\"button\": 4}");
            out.add(parseOne(o, at));
        }
        // The same button or phrase twice in one routine is a typo.
        Set<String> seen = new java.util.HashSet<>();
        for (Trigger t : out) {
            String k = t.kind == Kind.BUTTON ? "button " + t.button : t.kind == Kind.VOICE ? "voice " + t.text : null;
            if (k != null && !seen.add(k)) throw new Routine.Invalid("triggers: " + k + " is listed twice");
        }
        return out;
    }

    private static Trigger parseOne(JSONObject o, String at) throws Routine.Invalid {
        Trigger t = new Trigger();
        String key = null;
        for (Kind k : Kind.values()) {
            String n = k.name().toLowerCase(Locale.US);
            if (o.has(n)) {
                if (key != null) throw new Routine.Invalid(at + ": has both \"" + key + "\" and \"" + n + "\"; use one per trigger");
                key = n;
                t.kind = k;
            }
        }
        if (key == null) throw new Routine.Invalid(at + ": unknown trigger " + o + " (button, voice, schedule, event or mqtt)");
        String ifBusy = o.optString("if_busy", "");
        switch (t.kind) {
            case BUTTON: {
                allowOnly(o, at, "button");
                Object v = o.opt("button");
                int b = v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue())
                        ? ((Number) v).intValue() : -1;
                boolean ok = false;
                for (int x : BUTTONS) ok |= x == b;
                if (!ok) {
                    throw new Routine.Invalid(at + ".button: 4 (lightsaber), 5 (arm) or 6 (patrol); "
                            + "power, Wi-Fi and pairing can't be taken over");
                }
                t.button = b;
                t.replace = true;
                return t;
            }
            case VOICE: {
                allowOnly(o, at, "voice");
                String p = normalizePhrase(o.optString("voice", ""));
                if (!p.matches("[a-z']+( [a-z']+){1,5}")) {
                    throw new Routine.Invalid(at + ".voice: 2 to 6 plain words (letters only, write numbers as words), e.g. \"light show\"");
                }
                for (String b : BUILT_IN_PHRASES) {
                    if (startsWithWords(p, b) || startsWithWords(b, p)) {
                        throw new Routine.Invalid(at + ".voice: \"" + p + "\" overlaps the built-in phrase \"" + b + "\"");
                    }
                }
                t.text = p;
                t.replace = true;
                return t;
            }
            case SCHEDULE: {
                allowOnly(o, at, "schedule", "if_busy");
                JSONObject s = o.optJSONObject("schedule");
                if (s == null) throw new Routine.Invalid(at + ".schedule: e.g. {\"at\": \"07:30\", \"days\": [\"mon\", \"fri\"]}");
                allowOnly(s, at + ".schedule", "at", "every", "from", "to", "days");
                if (s.has("at") == s.has("every")) throw new Routine.Invalid(at + ".schedule: needs \"at\" or \"every\" (one of them)");
                if (s.has("at")) {
                    if (s.has("from") || s.has("to")) throw new Routine.Invalid(at + ".schedule: \"from\"/\"to\" go with \"every\"");
                    t.at = clock(s, "at", at + ".schedule.at");
                } else {
                    Object v = s.opt("every");
                    double d = v instanceof Number ? ((Number) v).doubleValue() : -1;
                    if (d != Math.rint(d) || d < MIN_EVERY_MIN || d > MAX_EVERY_MIN) {
                        throw new Routine.Invalid(at + ".schedule.every: minutes, a whole number from " + MIN_EVERY_MIN + " to " + MAX_EVERY_MIN);
                    }
                    t.every = (int) d;
                    if (s.has("from") != s.has("to")) throw new Routine.Invalid(at + ".schedule: \"from\" and \"to\" go together");
                    if (s.has("from")) {
                        t.from = clock(s, "from", at + ".schedule.from");
                        t.to = clock(s, "to", at + ".schedule.to");
                        if (t.from == t.to) throw new Routine.Invalid(at + ".schedule: \"from\" and \"to\" are the same time");
                    }
                }
                if (s.has("days")) {
                    JSONArray d = s.optJSONArray("days");
                    if (d == null || d.length() == 0) throw new Routine.Invalid(at + ".schedule.days: a list, e.g. [\"sat\", \"sun\"]");
                    t.days = 0;
                    for (int i = 0; i < d.length(); i++) {
                        int idx = indexOf(DAYS, d.optString(i).toLowerCase(Locale.US));
                        if (idx < 0) throw new Routine.Invalid(at + ".schedule.days[" + i + "]: one of " + join(DAYS));
                        t.days |= 1 << idx;
                    }
                }
                break;
            }
            case EVENT: {
                allowOnly(o, at, "event", "if_busy");
                String e = o.optString("event", "");
                if (!Routine.validName(e)) throw new Routine.Invalid(at + ".event: 1-32 characters: a-z, 0-9 and -, e.g. \"doorbell\"");
                t.text = e;
                break;
            }
            case MQTT: {
                allowOnly(o, at, "mqtt", "if_busy");
                JSONObject m = o.optJSONObject("mqtt");
                if (m == null) throw new Routine.Invalid(at + ".mqtt: e.g. {\"topic\": \"zigbee2mqtt/front-door\", \"field\": \"contact\", \"value\": false}");
                allowOnly(m, at + ".mqtt", "topic", "payload", "field", "value", "every_message");
                String topic = m.optString("topic", "");
                String bad = topicFilterProblem(topic);
                if (bad != null) throw new Routine.Invalid(at + ".mqtt.topic: " + bad);
                t.text = topic;
                if (m.has("payload") && m.has("field")) throw new Routine.Invalid(at + ".mqtt: \"payload\" or \"field\", not both");
                if (m.has("payload")) {
                    if (!(m.opt("payload") instanceof String)) throw new Routine.Invalid(at + ".mqtt.payload: text, e.g. \"ON\"");
                    t.payload = m.optString("payload").trim();
                }
                if (m.has("field") != m.has("value")) throw new Routine.Invalid(at + ".mqtt: \"field\" and \"value\" go together");
                if (m.has("field")) {
                    t.field = m.optString("field", "");
                    if (t.field.isEmpty()) throw new Routine.Invalid(at + ".mqtt.field: the name of a key in the JSON message");
                    t.value = m.opt("value");
                    if (t.value instanceof JSONObject || t.value instanceof JSONArray) {
                        throw new Routine.Invalid(at + ".mqtt.value: text, a number, true/false or null");
                    }
                }
                t.everyMessage = m.optBoolean("every_message", false);
                break;
            }
            default:
                throw new Routine.Invalid(at + ": unknown trigger");
        }
        if (!ifBusy.isEmpty() && !ifBusy.equals("skip") && !ifBusy.equals("replace")) {
            throw new Routine.Invalid(at + ".if_busy: \"skip\" (default) or \"replace\"");
        }
        t.replace = ifBusy.equals("replace");
        return t;
    }

    /** Lower case, single spaces, "R2-D2" spelled the way the speech model knows it. */
    static String normalizePhrase(String p) {
        p = p.toLowerCase(Locale.US).replace("r2-d2", "r two d two").replace("r2d2", "r two d two").replace('-', ' ');
        return p.trim().replaceAll("\\s+", " ");
    }

    private static boolean startsWithWords(String phrase, String prefix) {
        return phrase.equals(prefix) || phrase.startsWith(prefix + " ");
    }

    /** Why a subscription filter is refused, or null. The first level must be plain, so "#" can't flood R2. */
    static String topicFilterProblem(String f) {
        if (f.isEmpty() || f.length() > 200) return "1-200 characters, e.g. \"zigbee2mqtt/front-door\"";
        if (f.indexOf('\0') >= 0) return "contains a NUL character";
        String[] levels = f.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String l = levels[i];
            if (l.equals("#") && i != levels.length - 1) return "\"#\" may only be the last level";
            if ((l.contains("#") || l.contains("+")) && l.length() > 1) return "\"+\" and \"#\" must be a whole level";
        }
        if (levels[0].isEmpty() || levels[0].equals("+") || levels[0].equals("#") || levels[0].startsWith("$")) {
            return "the first level must be a plain name (not empty, a wildcard or $SYS)";
        }
        return null;
    }

    /** MQTT topic filter matching, with + (one level) and # (the rest). */
    static boolean topicMatches(String filter, String topic) {
        String[] f = filter.split("/", -1), t = topic.split("/", -1);
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals("#")) return true;
            if (i >= t.length) return false;
            if (!f[i].equals("+") && !f[i].equals(t[i])) return false;
        }
        return f.length == t.length;
    }

    /** Does an MQTT payload satisfy the trigger's payload / field condition? */
    static boolean payloadMatches(Trigger t, String payload) {
        if (t.payload != null) return t.payload.equals(payload.trim());
        if (t.field == null) return true;
        try {
            JSONObject j = new JSONObject(payload);
            if (!j.has(t.field)) return false;
            Object got = j.opt(t.field);
            if (t.value == null || t.value == JSONObject.NULL) return got == JSONObject.NULL;
            if (t.value instanceof Number && got instanceof Number) {
                return ((Number) t.value).doubleValue() == ((Number) got).doubleValue();
            }
            return String.valueOf(t.value).equals(String.valueOf(got));
        } catch (Exception e) {
            return false;
        }
    }

    private static int clock(JSONObject o, String key, String at) throws Routine.Invalid {
        String s = o.optString(key, "");
        if (!s.matches("\\d{1,2}:\\d{2}")) throw new Routine.Invalid(at + ": a time like \"07:30\" (24-hour clock)");
        int h = Integer.parseInt(s.substring(0, s.indexOf(':'))), m = Integer.parseInt(s.substring(s.indexOf(':') + 1));
        if (h > 23 || m > 59) throw new Routine.Invalid(at + ": " + s + " is not a time of day");
        return h * 60 + m;
    }

    private static void allowOnly(JSONObject o, String at, String... keys) throws Routine.Invalid {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (indexOf(keys, k) < 0) throw new Routine.Invalid(at + ": unknown key \"" + k + "\" (allowed: " + join(keys) + ")");
        }
    }

    private static int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return -1;
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String a : arr) sb.append(sb.length() > 0 ? ", " : "").append(a);
        return sb.toString();
    }

    // ---- schedules ------------------------------------------------------------------------

    /** Is the schedule due at this minute of the day on this weekday (0 Sunday)? */
    static boolean dueAt(Trigger t, int minuteOfDay, int weekday) {
        if (t.kind != Kind.SCHEDULE) return false;
        if (t.at >= 0) return t.at == minuteOfDay && (t.days & (1 << weekday)) != 0;
        if (t.from < 0) {
            return minuteOfDay % t.every == 0 && (t.days & (1 << weekday)) != 0;
        }
        // A window that crosses midnight (22:00-06:00) belongs to the day it started on.
        int day = weekday, since;
        if (t.from < t.to) {
            if (minuteOfDay < t.from || minuteOfDay > t.to) return false;
            since = minuteOfDay - t.from;
        } else if (minuteOfDay >= t.from) {
            since = minuteOfDay - t.from;
        } else if (minuteOfDay <= t.to) {
            since = minuteOfDay + 1440 - t.from;
            day = (weekday + 6) % 7;
        } else {
            return false;
        }
        return since % t.every == 0 && (t.days & (1 << day)) != 0;
    }

    // ---- the table of all routines' triggers ------------------------------------------------

    /** Every stored routine's triggers. Immutable; RoutineStore builds a new one after each change. */
    static final class Table {
        static final Table EMPTY = new Table(Collections.<String, List<Trigger>>emptyMap());

        final Map<String, List<Trigger>> byRoutine;
        private final Map<Integer, String> buttons = new HashMap<>();
        private final Map<String, String> phrases = new LinkedHashMap<>();

        Table(Map<String, List<Trigger>> byRoutine) {
            this.byRoutine = Collections.unmodifiableMap(new LinkedHashMap<>(byRoutine));
            for (Map.Entry<String, List<Trigger>> e : this.byRoutine.entrySet()) {
                for (Trigger t : e.getValue()) {
                    if (t.kind == Kind.BUTTON && !buttons.containsKey(t.button)) buttons.put(t.button, e.getKey());
                    if (t.kind == Kind.VOICE && !phrases.containsKey(t.text)) phrases.put(t.text, e.getKey());
                }
            }
        }

        /** The routine a button press runs instead of the built-in one, or null. */
        String button(int value) {
            return buttons.get(value);
        }

        /** Routine phrase -> routine name, for Voice. */
        Map<String, String> phrases() {
            return Collections.unmodifiableMap(phrases);
        }

        List<Hit> event(String name) {
            List<Hit> out = new ArrayList<>();
            for (Map.Entry<String, List<Trigger>> e : byRoutine.entrySet()) {
                for (Trigger t : e.getValue()) {
                    if (t.kind == Kind.EVENT && t.text.equals(name)) {
                        out.add(new Hit(e.getKey(), t.replace));
                        break;
                    }
                }
            }
            return out;
        }

        /** The MQTT topic filters to subscribe to. */
        Set<String> mqttTopics() {
            Set<String> out = new LinkedHashSet<>();
            for (List<Trigger> list : byRoutine.values()) {
                for (Trigger t : list) if (t.kind == Kind.MQTT) out.add(t.text);
            }
            return out;
        }

        /** Routines whose schedule is due in the minute that starts at this local time. */
        List<Hit> due(Calendar c) {
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int weekday = c.get(Calendar.DAY_OF_WEEK) - 1;
            List<Hit> out = new ArrayList<>();
            for (Map.Entry<String, List<Trigger>> e : byRoutine.entrySet()) {
                for (Trigger t : e.getValue()) {
                    if (dueAt(t, minute, weekday)) {
                        out.add(new Hit(e.getKey(), t.replace));
                        break;
                    }
                }
            }
            return out;
        }

        /** Names a button or phrase that another routine already uses, or null. */
        String conflict(String name, List<Trigger> mine) {
            for (Trigger t : mine) {
                String owner = t.kind == Kind.BUTTON ? buttons.get(t.button) : t.kind == Kind.VOICE ? phrases.get(t.text) : null;
                if (owner != null && !owner.equals(name)) {
                    return (t.kind == Kind.BUTTON ? "button " + t.button : "voice phrase \"" + t.text + "\"")
                            + " already starts routine " + owner;
                }
            }
            return null;
        }
    }

    /**
     * Tracks MQTT messages for the mqtt triggers: a trigger fires when a live message starts matching
     * (or on every matching message with every_message). Retained messages, which the broker replays
     * on each connect, only set what "before" was, so reconnecting never starts a routine.
     */
    static final class MqttWatch {
        private final Map<String, Boolean> matching = new HashMap<>(); // routine|index -> last message matched

        synchronized List<Hit> onMessage(Table table, String topic, String payload, boolean retained) {
            List<Hit> out = new ArrayList<>();
            for (Map.Entry<String, List<Trigger>> e : table.byRoutine.entrySet()) {
                List<Trigger> list = e.getValue();
                boolean fired = false;
                for (int i = 0; i < list.size(); i++) {
                    Trigger t = list.get(i);
                    if (t.kind != Kind.MQTT || !topicMatches(t.text, topic)) continue;
                    String key = e.getKey() + "|" + i + "|" + topic;
                    boolean now = payloadMatches(t, payload);
                    Boolean before = matching.put(key, now);
                    if (retained || !now || fired) continue;
                    if (t.everyMessage || before == null || !before) {
                        out.add(new Hit(e.getKey(), t.replace));
                        fired = true;
                    }
                }
            }
            if (matching.size() > 2000) matching.clear(); // a "+" filter on a busy tree: forget, don't grow
            return out;
        }

        synchronized void reset() {
            matching.clear();
        }
    }

    /** For listings: a trigger in a sentence, e.g. "at 07:30 on mon, fri". */
    static String describe(Trigger t) {
        switch (t.kind) {
            case BUTTON: return "button " + t.button;
            case VOICE: return "voice \"" + t.text + "\"";
            case EVENT: return "event " + t.text + (t.replace ? "" : " (if idle)");
            case MQTT: return "mqtt " + t.text + (t.replace ? "" : " (if idle)");
            case SCHEDULE: {
                StringBuilder sb = new StringBuilder();
                if (t.at >= 0) sb.append("at ").append(hhmm(t.at));
                else sb.append("every ").append(t.every).append(" min");
                if (t.from >= 0) sb.append(" from ").append(hhmm(t.from)).append(" to ").append(hhmm(t.to));
                if (t.days != 0x7f) {
                    sb.append(" on");
                    String sep = " ";
                    for (int i = 0; i < 7; i++) {
                        if ((t.days & (1 << i)) != 0) {
                            sb.append(sep).append(DAYS[i]);
                            sep = ", ";
                        }
                    }
                }
                return sb.append(t.replace ? "" : " (if idle)").toString();
            }
            default: return "?";
        }
    }

    private static String hhmm(int m) {
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** True once the clock has been set (from the network); before that, schedules wait. */
    static boolean clockSet(long now) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) >= 2024;
    }
}
