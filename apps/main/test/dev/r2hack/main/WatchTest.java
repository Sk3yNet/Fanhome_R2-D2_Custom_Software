package dev.r2hack.main;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Off-robot tests for watch-with-me: the cue file format, the session (position tracking, seeks,
 * pauses, Home Assistant going quiet) and stationary reactions. Run with apps/test.sh.
 */
public final class WatchTest {
    private static int passed, failed;
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList("wake", "dance", "shake-head", "turn-left"));

    /** Records fired cues as "position what". */
    static final class FakeHost implements WatchWithMe.Host {
        final Map<String, CueFile> files = new HashMap<>();
        final List<String> fired = new ArrayList<>();
        int changes;
        long now;

        public CueFile loadCue(String name) { return files.get(name); }

        public CueFile findCue(String title) {
            for (CueFile c : files.values()) if (c.matches(title)) return c;
            return null;
        }

        public void fire(CueFile file, CueFile.Cue cue) { fired.add(CueFile.clock(file.time(cue)) + " " + cue.what()); }

        public void log(String msg) { }

        public void changed() { changes++; }
    }

    static CueFile cue(String json) throws Exception {
        return CueFile.parse(new JSONObject(json), ACTIONS);
    }

    static String invalid(String json) {
        try {
            cue(json);
            return null;
        } catch (Routine.Invalid e) {
            return e.getMessage();
        } catch (Exception e) {
            return "not JSON: " + e;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(String what, Object want, Object got) {
        check(what + " (want " + want + ", got " + got + ")", want == null ? got == null : want.equals(got));
    }

    static void contains(String what, String text, String part) {
        check(what + " (\"" + text + "\" should contain \"" + part + "\")", text != null && text.contains(part));
    }

    public static void main(String[] args) throws Exception {
        format();
        session();
        seeking();
        homeAssistant();
        stationary();
        store();
        examples(args.length > 0 ? new File(args[0]) : new File("docs/watch-with-me"));
        System.out.println("watch-with-me: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final String DEMO = "{\"name\":\"demo\",\"title\":\"Demo\",\"match\":[\"Demo Film\"],\"cues\":["
            + "{\"at\":\"0:30\",\"routine\":\"cheer\",\"note\":\"hero arrives\"},"
            + "{\"at\":10,\"steps\":[{\"sound\":12},{\"head\":40},{\"wait\":1},{\"head\":0}]},"
            + "{\"at\":\"1:00:00\",\"steps\":[{\"action\":\"shake-head\"}]}]}";

    static void format() throws Exception {
        CueFile c = cue(DEMO);
        eq("sorted by time", 10.0, c.cues.get(0).at);
        eq("m:ss", 30.0, c.cues.get(1).at);
        eq("h:mm:ss", 3600.0, c.cues.get(2).at);
        eq("routine cue", "cheer", c.cues.get(1).what());
        eq("steps cue", "4 steps", c.cues.get(0).what());
        check("inline steps may run on the charger", c.cues.get(0).steps.whileCharging);
        check("match is case-insensitive", c.matches("Star Wars: DEMO FILM (1977)") && !c.matches("Other") && !c.matches(""));
        eq("time with tenths", 62.5, CueFile.time("1:02.5", "x"));
        eq("clock", "1:02:03", CueFile.clock(3723));
        eq("clock short", "0:07.5", CueFile.clock(7.5));

        contains("no driving", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"steps\":[{\"drive\":{\"power\":30,\"seconds\":1}}]}]}"),
                "cues[0].steps[0]: no driving");
        contains("no turning modes", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"steps\":[{\"repeat\":2,\"steps\":[{\"mode\":3}]}]}]}"),
                "cues[0].steps[0].steps[0]: no driving");
        contains("no turning actions", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"steps\":[{\"action\":\"dance\"}]}]}"), "no driving");
        contains("no move commands", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"steps\":[{\"cmd\":{\"cmd\":\"move\",\"power\":9}}]}]}"), "no driving");
        check("flash mode is fine", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"steps\":[{\"mode\":13}]}]}") == null);
        contains("step errors name the cue", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1},{\"at\":2,\"steps\":[{\"head\":200}]}]}"),
                "cues[0]: needs either");
        contains("nested step error", invalid("{\"name\":\"a\",\"cues\":[{\"at\":2,\"steps\":[{\"head\":200}]}]}"), "cues[0].steps[0].head");
        contains("bad time", invalid("{\"name\":\"a\",\"cues\":[{\"at\":\"1:75\",\"routine\":\"x\"}]}"), "60 or more");
        contains("bad time text", invalid("{\"name\":\"a\",\"cues\":[{\"at\":\"soon\",\"routine\":\"x\"}]}"), "cues[0].at");
        contains("unknown key", invalid("{\"name\":\"a\",\"cuez\":[]}"), "unknown key \"cuez\"");
        contains("empty cues", invalid("{\"name\":\"a\",\"cues\":[]}"), "non-empty");
        contains("short match", invalid("{\"name\":\"a\",\"match\":[\"x\"],\"cues\":[{\"at\":1,\"routine\":\"x\"}]}"), "match[0]");
        contains("offset range", invalid("{\"name\":\"a\",\"offset\":9999,\"cues\":[{\"at\":1,\"routine\":\"x\"}]}"), "offset");
        contains("timeout only for steps", invalid("{\"name\":\"a\",\"cues\":[{\"at\":1,\"routine\":\"x\",\"timeout\":5}]}"), "only for steps");
        eq("offset moves cues", 12.0, cue("{\"name\":\"a\",\"offset\":2,\"cues\":[{\"at\":10,\"routine\":\"x\"}]}")
                .time(cue("{\"name\":\"a\",\"offset\":2,\"cues\":[{\"at\":10,\"routine\":\"x\"}]}").cues.get(0)));
    }

    static JSONObject msg(String json) throws Exception {
        return new JSONObject(json);
    }

    /** Ticks every 250 ms of simulated time up to the given ms. */
    static void run(WatchWithMe w, FakeHost h, long until) {
        while (h.now < until) {
            h.now += 250;
            w.tick(h.now);
        }
    }

    static void session() throws Exception {
        FakeHost h = new FakeHost();
        h.files.put("demo", cue(DEMO));
        WatchWithMe w = new WatchWithMe(h);
        contains("start by name", w.update(msg("{\"state\":\"playing\",\"cue\":\"demo\",\"position\":0}"), false, h.now), "playing demo at 0:00");
        run(w, h, 9_000);
        check("nothing before the first cue", h.fired.isEmpty());
        run(w, h, 11_000);
        eq("first cue fires on time", Arrays.asList("0:10 4 steps"), h.fired);
        w.update(msg("{\"state\":\"paused\"}"), false, h.now);
        run(w, h, 60_000);
        eq("paused: nothing fires", 1, h.fired.size());
        eq("paused position holds", "0:11", w.status(h.now).optString("position_text"));
        w.update(msg("{\"state\":\"playing\"}"), false, h.now);
        run(w, h, 80_000);
        eq("resumed: second cue", Arrays.asList("0:10 4 steps", "0:30 cheer"), h.fired);
        eq("status: last", "0:30 cheer (hero arrives)", w.status(h.now).optString("last"));
        eq("status: next", "1:00:00", w.status(h.now).optJSONObject("next").optString("at"));

        w.setEnabled(false);
        w.update(msg("{\"state\":\"playing\",\"position\":3595}"), false, h.now);
        run(w, h, h.now + 10_000);
        eq("switched off: nothing fires", 2, h.fired.size());
        w.setEnabled(true);
        check("active again", w.active());

        contains("off ends it", w.update(msg("{\"state\":\"idle\"}"), false, h.now), "stopped watching demo");
        eq("off state", "off", w.status(h.now).optString("state"));
        check("not active when off", !w.active());

        try {
            w.update(msg("{\"state\":\"playing\",\"cue\":\"nope\"}"), false, h.now);
            check("unknown cue file refused", false);
        } catch (IllegalArgumentException e) {
            contains("unknown cue file", e.getMessage(), "no usable cue file named nope");
        }
        try {
            w.update(msg("{\"state\":\"rewinding\"}"), false, h.now);
            check("unknown state refused", false);
        } catch (IllegalArgumentException e) {
            contains("unknown state", e.getMessage(), "unknown state");
        }
        try {
            w.update(msg("{\"state\":\"playing\",\"cue\":\"demo\",\"position\":\"later\"}"), false, h.now);
            check("bad position refused", false);
        } catch (IllegalArgumentException e) {
            contains("bad position", e.getMessage(), "position");
        }
        contains("position as h:mm:ss", w.update(msg("{\"state\":\"paused\",\"cue\":\"demo\",\"position\":\"0:59:59\"}"), false, h.now), "at 59:59");
    }

    static void seeking() throws Exception {
        FakeHost h = new FakeHost();
        h.files.put("demo", cue(DEMO));
        WatchWithMe w = new WatchWithMe(h);
        w.update(msg("{\"state\":\"playing\",\"cue\":\"demo\",\"position\":5}"), false, h.now);
        // Skip ahead past both early cues: they don't fire.
        w.update(msg("{\"state\":\"playing\",\"position\":40}"), false, h.now);
        run(w, h, 5_000);
        check("seek forward skips cues", h.fired.isEmpty());
        // Back to 0:08: the 0:10 cue fires again when reached.
        w.update(msg("{\"state\":\"playing\",\"position\":8}"), false, h.now);
        run(w, h, h.now + 3_000);
        eq("seek back replays", Arrays.asList("0:10 4 steps"), h.fired);
        // A small correction backwards doesn't replay a cue.
        w.update(msg("{\"state\":\"playing\",\"position\":9.5}"), false, h.now);
        run(w, h, h.now + 2_000);
        eq("drift back: no replay", 1, h.fired.size());
        // A small correction forwards over a cue still fires it.
        w.update(msg("{\"state\":\"playing\",\"position\":27.5}"), false, h.now);
        w.update(msg("{\"state\":\"playing\",\"position\":30.5}"), false, h.now);
        run(w, h, h.now + 250);
        eq("drift forward over a cue fires it", Arrays.asList("0:10 4 steps", "0:30 cheer"), h.fired);

        // A cue noticed late (the robot was busy for 5 s) is skipped.
        FakeHost h2 = new FakeHost();
        h2.files.put("demo", cue(DEMO));
        WatchWithMe w2 = new WatchWithMe(h2);
        w2.update(msg("{\"state\":\"playing\",\"cue\":\"demo\",\"position\":0}"), false, 0);
        w2.tick(15_000);
        check("late cue skipped", h2.fired.isEmpty());
        eq("skips counted", 1, w2.status(15_000).optInt("skipped"));
    }

    static void homeAssistant() throws Exception {
        FakeHost h = new FakeHost();
        h.files.put("demo", cue(DEMO));
        WatchWithMe w = new WatchWithMe(h);
        contains("no matching file", w.update(msg("{\"state\":\"playing\",\"title\":\"Some Other Film\",\"position\":3}"), true, 0),
                "no cue file matches");
        eq("state without a file", "no cue file", w.status(0).optString("state"));
        contains("title picks the file", w.update(msg("{\"state\":\"playing\",\"title\":\"The Demo Film\",\"position\":3}"), true, 0),
                "playing demo");
        w.update(msg("{\"state\":\"playing\",\"title\":\"The Demo Film\",\"position\":null}"), true, 0);
        eq("null position keeps counting", "0:03", w.status(0).optString("position_text"));
        eq("source", "home assistant", w.status(0).optString("source"));
        h.now = 0;
        run(w, h, WatchWithMe.STALE_MS + 1_000);
        eq("goes quiet without updates", "lost sync", w.status(h.now).optString("state"));
        int n = h.fired.size();
        run(w, h, h.now + 60_000);
        eq("nothing fires after losing sync", n, h.fired.size());
        w.update(msg("{\"state\":\"playing\",\"title\":\"The Demo Film\",\"position\":5}"), true, h.now);
        eq("the next update picks it up", "playing", w.status(h.now).optString("state"));
        int before = h.changes;
        w.update(msg("{\"state\":\"playing\",\"title\":\"The Demo Film\",\"position\":5.2}"), true, h.now);
        eq("no change: no republish", before, h.changes);
        w.update(msg("{\"state\":\"playing\",\"title\":\"Some Other Film\",\"position\":5}"), true, h.now);
        eq("a different film ends it", "no cue file", w.status(h.now).optString("state"));
    }

    /** A stored routine run as a reaction keeps R2 in place. */
    static void stationary() throws Exception {
        RoutineTest.FakeHost h = new RoutineTest.FakeHost();
        Routine r = Routine.parse(new JSONObject("{\"name\":\"cheer\",\"steps\":[{\"sound\":1},{\"drive\":{\"power\":30,\"seconds\":2}},"
                + "{\"mode\":3},{\"mode\":13},{\"action\":\"dance\"},{\"action\":\"wake\"},{\"cmd\":{\"cmd\":\"move\",\"power\":5}},{\"head\":20}]}"),
                ACTIONS);
        h.fields.put("charging", 1);
        try {
            new RoutineRunner(h, h).stationary().whileCharging().run(r);
        } catch (Exception e) {
            check("stationary run: " + e, false);
        }
        eq("only the stationary steps ran", Arrays.asList("0 sound 1", "0 mode mode=13", "0 action wake", "0 head-angle angle=20"), h.log);
    }

    static void store() throws Exception {
        File dir = Files.createTempDirectory("cues").toFile();
        CueStore s = new CueStore(new File(dir, "c"), ACTIONS);
        s.save(new JSONObject(DEMO));
        eq("listed", Arrays.asList("demo"), s.names());
        eq("found by title", "demo", s.find("demo film").name);
        eq("not found", null, s.find("nothing"));
        try {
            s.save(new JSONObject("{\"name\":\"bad\",\"cues\":[{\"at\":1}]}"));
            check("bad file refused", false);
        } catch (Routine.Invalid e) {
            check("bad file not saved", !s.exists("bad"));
        }
        check("delete", s.delete("demo") && !s.exists("demo"));
    }

    /** The examples in docs/watch-with-me must all be valid. */
    static void examples(File dir) throws Exception {
        File[] files = dir.listFiles();
        check("examples found in " + dir, files != null && files.length > 0);
        if (files == null) return;
        Set<String> all = new HashSet<>(Routine.STEP_ACTIONS);
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            try {
                JSONObject j = new JSONObject(text);
                String name = j.has("cues") ? CueFile.parse(j, all).name : Routine.parse(j, all).name;
                eq(f.getName() + " name matches file", f.getName(), name + ".json");
            } catch (Exception e) {
                check(f.getName() + ": " + e.getMessage(), false);
            }
        }
    }
}
