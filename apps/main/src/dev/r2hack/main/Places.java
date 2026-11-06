package dev.r2hack.main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Places in the house that R2 can drive to (docs/places.md). Each place has a route taught by
 * driving R2 there from "home" with the joystick; R2 knows no map and has no wheel sensors, so a
 * trip is a replay of that drive. The way back is either taught too, or the route mirrored after a
 * taught turn-around. R2 keeps track of where he is (home, a place, or unknown) so he can chain
 * trips: kitchen -> office goes kitchen -> home -> office.
 *
 * Trips run as routines (RoutineRunner), so every routine safety rule and the agent's motion guard
 * apply. Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class Places {
    static final String HOME = "home", UNKNOWN = "unknown";
    static final int MAX_PLACES = 30;
    static final int MAX_SEGMENTS = 400;          // per route
    static final double MAX_ROUTE_S = 300;        // per route
    static final int MAX_POWER = 60;              // drive power a route may use (the web page teaches at up to 40)
    static final int MAX_SPOKEN = 5;
    static final int S_SET_OFF = 7, S_ARRIVED = 5; // sounds: acknowledgement before leaving, a reply on arrival
    static final long WARN_MS = 1500;             // between the warning chirp and setting off
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One drive command held for a time: the controller's move (power, angle -180..180). */
    static final class Segment {
        final int power, angle;
        final long ms;

        Segment(int power, int angle, long ms) {
            this.power = power;
            this.angle = angle;
            this.ms = ms;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("power", power).put("angle", angle).put("seconds", ms / 1000.0);
        }

        /** The same move seen from a robot facing the other way: left and right swap, forward and back stay. */
        Segment mirrored() {
            int a = angle == -180 ? 180 : -angle;
            return new Segment(power, a, ms);
        }

        @Override
        public String toString() {
            return power + "@" + angle + " " + ms + "ms";
        }
    }

    static final class Place {
        final String name;
        final List<String> spoken;
        final String area;
        final List<Segment> there;
        final List<Segment> back;   // empty: mirror "there" (needs a taught turn-around)
        final int marker;           // -1: none (printed markers come later)

        Place(String name, List<String> spoken, String area, List<Segment> there, List<Segment> back, int marker) {
            this.name = name;
            this.spoken = Collections.unmodifiableList(spoken);
            this.area = area;
            this.there = Collections.unmodifiableList(there);
            this.back = Collections.unmodifiableList(back);
            this.marker = marker;
        }

        Place withBack(List<Segment> b) {
            return new Place(name, spoken, area, there, b, marker);
        }

        JSONObject json() throws Exception {
            JSONObject j = new JSONObject().put("name", name).put("spoken", new JSONArray(spoken)).put("there", segmentsJson(there));
            if (!area.isEmpty()) j.put("area", area);
            if (!back.isEmpty()) j.put("back", segmentsJson(back));
            if (marker >= 0) j.put("marker", marker);
            return j;
        }

        /** For listings: no segment lists, just their size. */
        JSONObject summary() throws Exception {
            JSONObject j = new JSONObject().put("name", name).put("spoken", new JSONArray(spoken))
                    .put("there_seconds", seconds(there)).put("there_segments", there.size())
                    .put("way_back", back.isEmpty() ? "mirrored" : "taught");
            if (!back.isEmpty()) j.put("back_seconds", seconds(back));
            if (!area.isEmpty()) j.put("area", area);
            if (marker >= 0) j.put("marker", marker);
            return j;
        }
    }

    // ---- parsing -------------------------------------------------------------------------------

    static boolean validName(String n) {
        return Routine.validName(n) && !HOME.equals(n) && !UNKNOWN.equals(n);
    }

    /** What R2 listens for by default: "the kitchen", "the living room". */
    static String defaultSpoken(String name) {
        return "the " + name.replace('-', ' ');
    }

    static Place parsePlace(JSONObject j) throws Routine.Invalid {
        String name = j.optString("name", "");
        if (!validName(name)) {
            throw new Routine.Invalid("place name must be 1-32 characters: a-z, 0-9 and -, and not \"home\" or \"unknown\"");
        }
        for (Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("name") && !k.equals("spoken") && !k.equals("area") && !k.equals("there") && !k.equals("back")
                    && !k.equals("marker")) {
                throw new Routine.Invalid("unknown key \"" + k + "\" (allowed: name, spoken, area, there, back, marker)");
            }
        }
        List<String> spoken = new ArrayList<>();
        JSONArray sp = j.optJSONArray("spoken");
        if (j.has("spoken") && sp == null) throw new Routine.Invalid("spoken must be a list of phrases");
        for (int i = 0; sp != null && i < sp.length(); i++) {
            String s = normalizeSpoken(sp.optString(i, ""));
            if (s.isEmpty()) throw new Routine.Invalid("spoken[" + i + "]: use lowercase words a-z only, e.g. \"the kitchen\"");
            if (!spoken.contains(s)) spoken.add(s);
        }
        if (spoken.size() > MAX_SPOKEN) throw new Routine.Invalid("at most " + MAX_SPOKEN + " spoken names");
        if (spoken.isEmpty()) spoken.add(defaultSpoken(name));
        String area = j.optString("area", "").trim();
        if (area.length() > 64) throw new Routine.Invalid("area: at most 64 characters");
        List<Segment> there = parseSegments(j.optJSONArray("there"), "there", true);
        List<Segment> back = parseSegments(j.optJSONArray("back"), "back", false);
        int marker = -1;
        if (j.has("marker")) {
            Object m = j.opt("marker");
            if (!(m instanceof Integer) || (Integer) m < 0 || (Integer) m > 586) throw new Routine.Invalid("marker: a tag number 0-586");
            marker = (Integer) m;
        }
        return new Place(name, spoken, area, there, back, marker);
    }

    /** Lowercase words a-z separated by single spaces, or "" if it has anything else. */
    static String normalizeSpoken(String s) {
        String t = s.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
        return t.matches("[a-z']+( [a-z']+){0,5}") ? t : "";
    }

    static List<Segment> parseSegments(JSONArray arr, String where, boolean required) throws Routine.Invalid {
        List<Segment> out = new ArrayList<>();
        if (arr == null) {
            if (required) throw new Routine.Invalid(where + " must be a list of drive segments {\"power\", \"angle\", \"seconds\"}");
            return out;
        }
        if (required && arr.length() == 0) throw new Routine.Invalid(where + " is empty");
        if (arr.length() > MAX_SEGMENTS) throw new Routine.Invalid(where + ": more than " + MAX_SEGMENTS + " segments");
        double total = 0;
        for (int i = 0; i < arr.length(); i++) {
            String at = where + "[" + i + "]";
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new Routine.Invalid(at + ": {\"power\": 0-" + MAX_POWER + ", \"angle\": -180 to 180, \"seconds\": n}");
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!k.equals("power") && !k.equals("angle") && !k.equals("seconds")) throw new Routine.Invalid(at + ": unknown key \"" + k + "\"");
            }
            int power = whole(o, "power", 0, MAX_POWER, at);
            int angle = o.has("angle") ? whole(o, "angle", -180, 180, at) : 0;
            Object sv = o.opt("seconds");
            if (!(sv instanceof Number)) throw new Routine.Invalid(at + ".seconds is required");
            double sec = ((Number) sv).doubleValue();
            if (Double.isNaN(sec) || sec < 0.05 || sec > 60) throw new Routine.Invalid(at + ".seconds: 0.05 to 60");
            total += sec;
            out.add(new Segment(power, angle, Math.round(sec * 1000)));
        }
        if (total > MAX_ROUTE_S) throw new Routine.Invalid(where + ": longer than " + (int) MAX_ROUTE_S + " s in all");
        return out;
    }

    private static int whole(JSONObject o, String key, int lo, int hi, String at) throws Routine.Invalid {
        Object v = o.opt(key);
        if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())) {
            throw new Routine.Invalid(at + "." + key + ": a whole number is needed");
        }
        int n = ((Number) v).intValue();
        if (n < lo || n > hi) throw new Routine.Invalid(at + "." + key + ": " + n + " is outside " + lo + " to " + hi);
        return n;
    }

    static JSONArray segmentsJson(List<Segment> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Segment s : list) a.put(s.json());
        return a;
    }

    static double seconds(List<Segment> list) {
        long ms = 0;
        for (Segment s : list) ms += s.ms;
        return ms / 1000.0;
    }

    // ---- recording a route ------------------------------------------------------------------------

    /**
     * Turns the joystick's stream of move commands (repeated every ~250 ms while held) into drive
     * segments. Commands within a little power and angle of the segment's first one continue it, so a
     * steady hand gives few segments;
     * each segment lasts until the next different command, a stop, or the agent's 3 s motion watchdog.
     */
    static final class Recorder {
        static final long WATCHDOG_MS = 3000;   // the agent stops a move that isn't repeated
        private final List<Segment> done = new ArrayList<>();
        private int power, angle;
        private long since = -1, last;
        private long sumP, sumA, n; // the segment's average; angles near +/-180 are averaged as they come (rare)
        private String problem;

        /** A move command R2 just sent to the controller, at time now (ms). */
        synchronized void move(long now, int p, int a) {
            if (p > MAX_POWER && problem == null) problem = "R2 was driven at power " + p + "; routes allow up to " + MAX_POWER + ", so teach it again more slowly";
            p = Math.max(0, Math.min(MAX_POWER, p));
            if (a > 180) a -= 360;
            a = Math.max(-180, Math.min(180, a));
            if (p == 0) a = 0;
            if (since >= 0 && now - last > WATCHDOG_MS) close(last + WATCHDOG_MS); // the watchdog stopped it in between
            if (since >= 0 && p > 0 && Math.abs(p - power) <= POWER_SLACK && angleDiff(a, angle) <= ANGLE_SLACK) {
                last = now;
                sumP += p;
                sumA += a;
                n++;
                return;
            }
            if (since >= 0) close(now);
            if (p > 0) {
                power = p;
                angle = a;
                sumP = p;
                sumA = a;
                n = 1;
                since = now;
                last = now;
            }
        }

        static final int POWER_SLACK = 2, ANGLE_SLACK = 4; // a hand on a joystick wobbles this much

        private static int angleDiff(int a, int b) {
            int d = Math.abs(a - b) % 360;
            return d > 180 ? 360 - d : d;
        }

        private void close(long end) {
            long ms = Math.min(end, last + WATCHDOG_MS) - since;
            if (ms >= 50) {
                if (done.size() < MAX_SEGMENTS) {
                    int avgA = (int) Math.round((double) sumA / n);
                    if (angleDiff(avgA, angle) > ANGLE_SLACK) avgA = angle; // wrapped around 180: keep the first
                    done.add(new Segment((int) Math.round((double) sumP / n), avgA, ms));
                } else {
                    problem = "the route has more than " + MAX_SEGMENTS + " segments; drive in steadier, longer moves";
                }
            }
            since = -1;
        }

        /** Something that stopped the recording from being usable, or null. */
        synchronized String problem() {
            return problem;
        }

        /** Ends the recording at time now and returns the segments, with pauses between moves dropped. */
        synchronized List<Segment> finish(long now) {
            if (since >= 0) close(now);
            return new ArrayList<>(done);
        }
    }

    // ---- planning a trip --------------------------------------------------------------------------

    /** The drives of one trip, or the reason it can't be made. */
    static final class Trip {
        final List<Segment> segments;
        final String error;
        final String description;
        /** Where R2 is when the trip ends. */
        final String end;

        Trip(List<Segment> segments, String error, String description) {
            this(segments, error, description, null);
        }

        Trip(List<Segment> segments, String error, String description, String end) {
            this.segments = segments;
            this.error = error;
            this.description = description;
            this.end = end;
        }
    }

    /** How to get from one place (or home) to another. turnAround: the taught spin, empty if none. */
    static Trip plan(String from, String to, Map<String, Place> places, List<Segment> turnAround) {
        if (UNKNOWN.equals(from)) {
            return new Trip(null, "I don't know where I am. Put me back at home and tell me (\"you're home\"), then try again.", null);
        }
        if (from.equals(to)) return new Trip(new ArrayList<Segment>(), null, "already at " + to, to);
        if (!HOME.equals(to) && !places.containsKey(to)) return new Trip(null, "no place named " + to, null);
        List<Segment> out = new ArrayList<>();
        String desc;
        if (!HOME.equals(from)) {
            Place p = places.get(from);
            if (p == null) return new Trip(null, "I'm at \"" + from + "\", which isn't a place any more; put me back at home", null);
            List<Segment> back = wayBack(p, turnAround);
            if (back == null) {
                return new Trip(null, "I don't know the way back from " + from + ": teach it, or teach the turn-around", null);
            }
            out.addAll(back);
            desc = from + " -> home";
        } else {
            desc = "home";
        }
        if (!HOME.equals(to)) {
            out.addAll(places.get(to).there);
            desc += " -> " + to;
        }
        if (seconds(out) > MAX_TRIP_S) return new Trip(null, "the trip would take longer than " + (int) MAX_TRIP_S + " s", null);
        return new Trip(out, null, desc, to);
    }

    /**
     * Smarter patrol: from where R2 is, visit every place in turn (each one a trip via home) and end
     * at home, pausing pauseMs at each. Places without a way back end the tour there.
     */
    static Trip tour(String from, Map<String, Place> places, List<Segment> turnAround) {
        if (places.isEmpty()) return new Trip(null, "no places taught yet", null);
        List<Segment> out = new ArrayList<>();
        StringBuilder desc = new StringBuilder("tour: ");
        String at = from;
        for (String p : places.keySet()) {
            Trip leg = plan(at, p, places, turnAround);
            if (leg.error != null) {
                if (at.equals(from)) return leg;
                break; // can't get from the last place to the next: end the tour where we are
            }
            out.addAll(leg.segments);
            out.add(new Segment(0, 0, TOUR_PAUSE_MS)); // look around: stand still a moment
            desc.append(p).append(", ");
            at = p;
        }
        Trip home = plan(at, HOME, places, turnAround);
        if (home.error == null) {
            out.addAll(home.segments);
            desc.append("home");
            at = HOME;
        } else {
            desc.append("ends at ").append(at);
        }
        if (seconds(out) > MAX_TRIP_S) return new Trip(null, "the tour would take longer than " + (int) MAX_TRIP_S + " s; teach shorter routes", null);
        return new Trip(out, null, desc.toString(), at);
    }

    static final double MAX_TRIP_S = 700; // with the timeout margin this stays inside Routine.MAX_TIMEOUT_S

    static final long TOUR_PAUSE_MS = 3000;

    /** The taught way back, or the way there mirrored between two turn-arounds; null if neither is known. */
    static List<Segment> wayBack(Place p, List<Segment> turnAround) {
        if (!p.back.isEmpty()) return new ArrayList<>(p.back);
        if (turnAround.isEmpty()) return null;
        List<Segment> out = new ArrayList<>(turnAround);
        for (int i = p.there.size() - 1; i >= 0; i--) out.add(p.there.get(i).mirrored());
        out.addAll(turnAround); // face the way the route starts, as when it was taught
        return out;
    }

    /**
     * The trip as a routine: a warning chirp, a pause, the drives, an arrival sound. The timeout is the
     * drive time plus a quarter, so a stalled trip can't run on.
     */
    static Routine trip(String to, Trip t) {
        List<Routine.Step> steps = new ArrayList<>();
        Routine.Step chirp = new Routine.Step();
        chirp.kind = Routine.Kind.SOUND;
        chirp.number = S_SET_OFF;
        steps.add(chirp);
        Routine.Step wait = new Routine.Step();
        wait.kind = Routine.Kind.WAIT;
        wait.ms = WARN_MS;
        steps.add(wait);
        long drive = 0;
        for (Segment s : t.segments) {
            Routine.Step d = new Routine.Step();
            d.kind = Routine.Kind.DRIVE;
            d.power = s.power;
            d.angle = s.angle;
            d.ms = s.ms;
            drive += s.ms;
            steps.add(d);
        }
        Routine.Step done = new Routine.Step();
        done.kind = Routine.Kind.SOUND;
        done.number = S_ARRIVED;
        steps.add(done);
        long timeout = WARN_MS + drive + drive / 4 + 10_000;
        return Routine.built("go-to-" + to, t.description, Math.min(timeout, (long) (Routine.MAX_TIMEOUT_S * 1000)), steps, new JSONObject());
    }

    /** True if running r may move R2 (so afterwards he no longer knows where he is). */
    static boolean moves(Routine r) {
        return moves(r.steps);
    }

    static boolean moves(List<Routine.Step> steps) {
        for (Routine.Step s : steps) {
            switch (s.kind) {
                case DRIVE:
                    if (s.power > 0) return true;
                    break;
                case MODE:
                    if (movingMode(s.number)) return true;
                    break;
                case CMD: {
                    String c = s.json.optString("cmd");
                    if ("move".equals(c) && s.json.optInt("power") > 0) return true;
                    if ("mode".equals(c) && movingMode(s.json.optInt("mode"))) return true;
                    break;
                }
                case ACTION:
                    if (movingAction(s.name)) return true;
                    break;
                case ROUTINE:
                    return true; // can't tell without loading it: assume it moves
                case REPEAT:
                    if (moves(s.steps)) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    static boolean movingMode(int m) {
        return m == 2 || m == 3 || m == 4 || m == 5 || m == 9 || m == 12;
    }

    static boolean movingAction(String a) {
        switch (a) {
            case "turn-left": case "turn-right": case "turn-around": case "forward": case "circle": case "patrol": case "dance":
                return true;
            default:
                return false;
        }
    }

    /** Voice phrase -> action ("goto:<place>"), for the on-robot recognizer. */
    static Map<String, String> phrases(Iterable<Place> list) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("go home", "goto:" + HOME);
        m.put("come home", "goto:" + HOME);
        for (Place p : list) for (String s : p.spoken) m.put("go to " + s, "goto:" + p.name);
        return m;
    }

    // ---- storage -------------------------------------------------------------------------------

    /**
     * files/places.json in R2 Main's data: the places, the taught turn-around, where R2 is, and
     * whether the charger counts as home. Written whole each time (it's small), through a temp file.
     */
    static final class Store {
        private final File file;
        private final Map<String, Place> places = new LinkedHashMap<>();
        private List<Segment> turnAround = new ArrayList<>();
        private String location = UNKNOWN;
        private boolean chargerIsHome;
        private String loadProblem;

        Store(File file) {
            this.file = file;
            load();
        }

        private void load() {
            if (!file.isFile()) return;
            try {
                JSONObject j = new JSONObject(read(file));
                JSONArray a = j.optJSONArray("places");
                for (int i = 0; a != null && i < a.length(); i++) {
                    try {
                        Place p = parsePlace(a.getJSONObject(i));
                        places.put(p.name, p);
                    } catch (Exception e) {
                        loadProblem = "place " + i + " not loaded: " + e.getMessage();
                    }
                }
                turnAround = parseSegments(j.optJSONArray("turn_around"), "turn_around", false);
                String loc = j.optString("location", UNKNOWN);
                location = HOME.equals(loc) || places.containsKey(loc) ? loc : UNKNOWN;
                chargerIsHome = j.optBoolean("charger_is_home", false);
            } catch (Exception e) {
                loadProblem = "places.json not loaded: " + e.getMessage();
            }
        }

        synchronized String loadProblem() {
            return loadProblem;
        }

        synchronized List<Place> list() {
            return new ArrayList<>(places.values());
        }

        synchronized Map<String, Place> map() {
            return new LinkedHashMap<>(places);
        }

        synchronized List<String> names() {
            return new ArrayList<>(places.keySet());
        }

        synchronized Place get(String name) {
            return places.get(name);
        }

        synchronized List<Segment> turnAround() {
            return new ArrayList<>(turnAround);
        }

        synchronized String location() {
            return location;
        }

        synchronized boolean chargerIsHome() {
            return chargerIsHome;
        }

        /** Saves (adds or replaces) a place. */
        synchronized void put(Place p) throws Routine.Invalid {
            if (!places.containsKey(p.name) && places.size() >= MAX_PLACES) throw new Routine.Invalid("already " + MAX_PLACES + " places; delete some first");
            for (Place other : places.values()) {
                if (other.name.equals(p.name)) continue;
                for (String s : p.spoken) {
                    if (other.spoken.contains(s)) throw new Routine.Invalid("\"" + s + "\" is already a name of " + other.name);
                }
            }
            Place old = places.put(p.name, p);
            try {
                save();
            } catch (Routine.Invalid e) {
                if (old != null) places.put(p.name, old);
                else places.remove(p.name);
                throw e;
            }
        }

        synchronized boolean delete(String name) throws Routine.Invalid {
            if (places.remove(name) == null) return false;
            if (name.equals(location)) location = UNKNOWN;
            save();
            return true;
        }

        synchronized void setTurnAround(List<Segment> t) throws Routine.Invalid {
            turnAround = new ArrayList<>(t);
            save();
        }

        synchronized void setChargerIsHome(boolean on) throws Routine.Invalid {
            chargerIsHome = on;
            save();
        }

        /** Where R2 is now: home, a place's name, or unknown. Returns false if nothing changed. */
        synchronized boolean setLocation(String loc) {
            if (!HOME.equals(loc) && !places.containsKey(loc)) loc = UNKNOWN;
            if (loc.equals(location)) return false;
            location = loc;
            try {
                save();
            } catch (Routine.Invalid ignored) {
                // kept in memory; the next save writes it
            }
            return true;
        }

        private void save() throws Routine.Invalid {
            File tmp = new File(file.getPath() + ".tmp");
            try {
                JSONArray a = new JSONArray();
                for (Place p : places.values()) a.put(p.json());
                JSONObject j = new JSONObject().put("places", a).put("turn_around", segmentsJson(turnAround))
                        .put("location", location).put("charger_is_home", chargerIsHome);
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("can't create " + dir);
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(j.toString().getBytes(UTF8));
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                if (!tmp.renameTo(file)) throw new Exception("rename failed");
            } catch (Exception e) {
                tmp.delete();
                throw new Routine.Invalid("could not save places: " + e.getMessage());
            }
        }

        private static String read(File f) throws Exception {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), UTF8);
            } finally {
                in.close();
            }
        }
    }
}
