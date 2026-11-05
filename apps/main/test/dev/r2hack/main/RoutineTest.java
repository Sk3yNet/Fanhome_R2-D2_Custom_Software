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
 * Off-robot tests for the routine format, interpreter and store. No test framework: run with
 * apps/test.sh. Time is simulated, so the tests take milliseconds.
 */
public final class RoutineTest {
    private static int passed, failed;
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList("wake", "dance", "leia"));

    /** A pretend robot: records what it is sent, with the simulated time. */
    static final class FakeHost implements RoutineRunner.Host, RoutineRunner.Clock {
        long now;
        final List<String> log = new ArrayList<>();
        final Map<String, Integer> fields = new HashMap<>();
        final Map<String, Routine> stored = new HashMap<>();
        String error = "";
        long soundUntil;
        /** Called on every sleep, to change the robot's state over time. */
        Runnable onTick;

        FakeHost() {
            fields.put("battery", 80);
            fields.put("charging", 0);
        }

        public long now() { return now; }

        public void sleep(long ms) {
            now += ms;
            if (onTick != null) onTick.run();
        }

        /** Logged as "head-angle angle=-40": the command, then its values sorted (org.json's key order varies). */
        public void send(JSONObject cmd) {
            java.util.TreeMap<String, Object> values = new java.util.TreeMap<>();
            for (java.util.Iterator<?> it = cmd.keys(); it.hasNext(); ) {
                String k = (String) it.next();
                if (!k.equals("cmd")) values.put(k, cmd.opt(k));
            }
            StringBuilder sb = new StringBuilder(now + " " + cmd.optString("cmd"));
            for (Map.Entry<String, Object> e : values.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            log.add(sb.toString());
        }

        public void playSound(int n) {
            log.add(now + " sound " + n);
            soundUntil = now + 1000;
        }

        public boolean soundPlaying() { return now < soundUntil; }

        public void stopSound() { log.add(now + " sound stop"); }

        public void runAction(String name) { log.add(now + " action " + name); }

        public Routine load(String name) { return stored.get(name); }

        public int field(String name) {
            Integer v = fields.get(name);
            return v == null ? -1 : v;
        }

        public String error() { return error; }

        String run(Routine r) {
            try {
                new RoutineRunner(this, this).run(r);
                return "finished";
            } catch (RoutineRunner.Abort a) {
                return "abort: " + a.getMessage();
            } catch (InterruptedException e) {
                return "interrupted";
            } catch (Exception e) {
                return "exception: " + e;
            }
        }
    }

    static Set<String> actions() {
        return ACTIONS;
    }

    static Routine parse(String json) throws Exception {
        return Routine.parse(new JSONObject(json), ACTIONS);
    }

    static String invalid(String json) {
        try {
            parse(json);
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
        parsing();
        running();
        safety();
        waitUntil();
        nesting();
        store();
        examples(args.length > 0 ? new File(args[0]) : new File("docs/routines"));
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void parsing() throws Exception {
        Routine r = parse("{\"name\":\"all-kinds\",\"description\":\"d\",\"timeout\":30,\"steps\":["
                + "{\"wait\":0.5},{\"sound\":7,\"until_done\":true},{\"head\":-130},{\"mode\":3},"
                + "{\"drive\":{\"power\":30,\"angle\":180,\"seconds\":1.5}},{\"led\":{\"r\":2,\"g\":5}},"
                + "{\"cmd\":{\"cmd\":\"lcd\",\"s\":2,\"l\":1}},{\"action\":\"dance\"},"
                + "{\"wait_until\":{\"field\":\"arm\",\"eq\":1},\"timeout\":5,\"else\":\"continue\"},"
                + "{\"repeat\":2,\"steps\":[{\"head\":10}]},{\"routine\":\"other\"}]}");
        eq("step count", 12, r.stepCount());
        eq("timeout", 30_000L, r.timeoutMs);
        eq("default timeout", 120_000L, parse("{\"name\":\"a\",\"steps\":[{\"wait\":1}]}").timeoutMs);

        contains("bad name", invalid("{\"name\":\"Bad Name\",\"steps\":[{\"wait\":1}]}"), "name must be");
        contains("missing name", invalid("{\"steps\":[{\"wait\":1}]}"), "name must be");
        contains("unknown top key", invalid("{\"name\":\"a\",\"stpes\":[]}"), "unknown key \"stpes\"");
        contains("no steps", invalid("{\"name\":\"a\",\"steps\":[]}"), "steps is empty");
        contains("unknown step", invalid("{\"name\":\"a\",\"steps\":[{\"jump\":1}]}"), "steps[0]: unknown step");
        contains("two kinds in one step", invalid("{\"name\":\"a\",\"steps\":[{\"wait\":1,\"head\":3}]}"), "use one per step");
        contains("head range", invalid("{\"name\":\"a\",\"steps\":[{\"wait\":1},{\"head\":140}]}"), "steps[1].head: 140 is outside -130 to 130");
        contains("head whole number", invalid("{\"name\":\"a\",\"steps\":[{\"head\":1.5}]}"), "whole number");
        contains("wait range", invalid("{\"name\":\"a\",\"steps\":[{\"wait\":-1}]}"), "outside");
        contains("wait not a number", invalid("{\"name\":\"a\",\"steps\":[{\"wait\":\"1\"}]}"), "a number is needed");
        contains("drive seconds required", invalid("{\"name\":\"a\",\"steps\":[{\"drive\":{\"power\":10}}]}"), "drive.seconds is required");
        contains("drive too long", invalid("{\"name\":\"a\",\"steps\":[{\"drive\":{\"power\":10,\"seconds\":61}}]}"), "outside");
        contains("led value", invalid("{\"name\":\"a\",\"steps\":[{\"led\":{\"r\":7}}]}"), "led.r");
        contains("led key", invalid("{\"name\":\"a\",\"steps\":[{\"led\":{\"x\":1}}]}"), "unknown key \"x\"");
        contains("cmd needs cmd", invalid("{\"name\":\"a\",\"steps\":[{\"cmd\":{\"mode\":1}}]}"), "a controller command");
        contains("forbidden cmd", invalid("{\"name\":\"a\",\"steps\":[{\"cmd\":{\"cmd\":\"shut-down\"}}]}"), "not allowed");
        contains("unknown action", invalid("{\"name\":\"a\",\"steps\":[{\"action\":\"stop\"}]}"), "not a built-in action");
        contains("wait_until field", invalid("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"speed\",\"eq\":1}}]}"), "field: one of");
        contains("wait_until op", invalid("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\"}}]}"), "needs a comparison");
        contains("wait_until two ops", invalid("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\",\"eq\":1,\"ne\":2}}]}"), "one comparison");
        contains("wait_until else", invalid("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\",\"eq\":1},\"else\":\"retry\"}]}"), "\"stop\" or \"continue\"");
        contains("empty repeat", invalid("{\"name\":\"a\",\"steps\":[{\"repeat\":2,\"steps\":[]}]}"), "non-empty steps");
        contains("repeat count", invalid("{\"name\":\"a\",\"steps\":[{\"repeat\":0,\"steps\":[{\"wait\":1}]}]}"), "outside 1 to");
        contains("timeout cap", invalid("{\"name\":\"a\",\"timeout\":1000,\"steps\":[{\"wait\":1}]}"), "timeout");
        String deep = "{\"wait\":1}";
        for (int i = 0; i < Routine.MAX_DEPTH; i++) deep = "{\"repeat\":1,\"steps\":[" + deep + "]}";
        contains("nesting", invalid("{\"name\":\"a\",\"steps\":[" + deep + "]}"), "nest more than");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= Routine.MAX_STEPS; i++) many.append(i > 0 ? "," : "").append("{\"wait\":0}");
        contains("too many steps", invalid("{\"name\":\"a\",\"steps\":[" + many + "]}"), "more than " + Routine.MAX_STEPS);
    }

    static void running() throws Exception {
        FakeHost h = new FakeHost();
        Routine r = parse("{\"name\":\"a\",\"steps\":[{\"sound\":8},{\"wait\":0.6},{\"head\":-40},{\"mode\":3},"
                + "{\"led\":{\"r\":2}},{\"action\":\"dance\"},{\"repeat\":2,\"steps\":[{\"head\":5},{\"wait\":1}]}]}");
        eq("plain run", "finished", h.run(r));
        eq("plain run log", Arrays.asList(
                "0 sound 8",
                "600 head-angle angle=-40",
                "600 mode mode=3",
                "600 led r=2",
                "600 action dance",
                "600 head-angle angle=5",
                "1600 head-angle angle=5"), h.log);
        eq("plain run time", 2600L, h.now);

        h = new FakeHost();
        eq("sound wait", "finished", h.run(parse("{\"name\":\"a\",\"steps\":[{\"sound\":1,\"until_done\":true},{\"head\":0}]}")));
        eq("sound wait ends when the sound does", "1000 head-angle angle=0", h.log.get(1));

        h = new FakeHost();
        eq("drive", "finished", h.run(parse("{\"name\":\"a\",\"steps\":[{\"drive\":{\"power\":30,\"angle\":180,\"seconds\":1.2}}]}")));
        eq("drive repeats and stops", Arrays.asList(
                "0 move angle=180 power=30",
                "500 move angle=180 power=30",
                "1000 move angle=180 power=30",
                "1200 move angle=0 power=0"), h.log);

        // A cancel (Thread.interrupt) ends the routine; a drive still ends with a stop command.
        h = new FakeHost();
        h.onTick = new Runnable() {
            public void run() {
                Thread.currentThread().interrupt();
            }
        };
        eq("interrupt", "interrupted", h.run(parse("{\"name\":\"a\",\"steps\":[{\"drive\":{\"power\":30,\"seconds\":5}},{\"head\":1}]}")));
        contains("interrupt stops driving", h.log.get(h.log.size() - 1), "power=0");
        Thread.interrupted(); // clear it for the next tests
    }

    static void safety() throws Exception {
        FakeHost h = new FakeHost();
        contains("overall timeout", h.run(parse("{\"name\":\"a\",\"timeout\":5,\"steps\":[{\"wait\":10},{\"head\":1}]}")), "timeout after 5 s");
        check("timeout stops before later steps", h.log.isEmpty());

        h = new FakeHost();
        h.fields.put("charging", 1);
        contains("charging refuses to start", h.run(parse("{\"name\":\"a\",\"steps\":[{\"head\":1}]}")), "on the charger");
        check("nothing sent while charging", h.log.isEmpty());
        eq("while_charging runs", "finished", h.run(parse("{\"name\":\"a\",\"while_charging\":true,\"steps\":[{\"head\":1}]}")));

        final FakeHost h2 = new FakeHost();
        h2.onTick = new Runnable() {
            public void run() {
                if (h2.now >= 1000) h2.fields.put("charging", 2);
            }
        };
        contains("put on the charger mid-way", h2.run(parse("{\"name\":\"a\",\"steps\":[{\"wait\":3},{\"head\":1}]}")), "on the charger");

        h = new FakeHost();
        h.fields.put("battery", 9);
        contains("low battery", h.run(parse("{\"name\":\"a\",\"steps\":[{\"head\":1}]}")), "battery low");
        h.fields.put("battery", -1);
        eq("battery unknown yet", "finished", h.run(parse("{\"name\":\"a\",\"steps\":[{\"head\":1}]}")));

        final FakeHost h3 = new FakeHost();
        h3.error = "0";
        h3.onTick = new Runnable() {
            public void run() {
                if (h3.now >= 500) h3.error = "motor stall";
            }
        };
        contains("new controller error", h3.run(parse("{\"name\":\"a\",\"steps\":[{\"wait\":2}]}")), "controller error: motor stall");
        h = new FakeHost();
        h.error = "old";
        eq("an error there before the start doesn't stop it", "finished", h.run(parse("{\"name\":\"a\",\"steps\":[{\"wait\":1}]}")));
    }

    static void waitUntil() throws Exception {
        final FakeHost h = new FakeHost();
        h.fields.put("arm", 0);
        h.onTick = new Runnable() {
            public void run() {
                if (h.now >= 700) h.fields.put("arm", 1);
            }
        };
        eq("wait_until met", "finished", h.run(parse("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\",\"eq\":1}},{\"head\":1}]}")));
        eq("wait_until goes on once met", "700 head-angle angle=1", h.log.get(0));

        FakeHost h2 = new FakeHost();
        h2.fields.put("arm", 0);
        contains("wait_until timeout stops", h2.run(parse("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\",\"ge\":1},\"timeout\":2},{\"head\":1}]}")), "waited 2.0 s for arm ge 1 (it is 0)");
        check("nothing after a failed wait_until", h2.log.isEmpty());

        FakeHost h3 = new FakeHost();
        h3.fields.put("arm", 0);
        eq("wait_until else continue", "finished", h3.run(parse("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"arm\",\"eq\":1},\"timeout\":2,\"else\":\"continue\"},{\"head\":1}]}")));
        eq("continue after the timeout", "2000 head-angle angle=1", h3.log.get(0));

        FakeHost h4 = new FakeHost();
        eq("battery lt", "finished", h4.run(parse("{\"name\":\"a\",\"steps\":[{\"wait_until\":{\"field\":\"battery\",\"lt\":90},\"timeout\":1}]}")));
        eq("lt holds at once", 0L, h4.now);
    }

    static void nesting() throws Exception {
        FakeHost h = new FakeHost();
        h.stored.put("inner", parse("{\"name\":\"inner\",\"steps\":[{\"head\":7}]}"));
        eq("calls another routine", "finished", h.run(parse("{\"name\":\"outer\",\"steps\":[{\"routine\":\"inner\"},{\"head\":8}]}")));
        eq("inner steps run in place", 2, h.log.size());
        contains("missing routine", new FakeHost().run(parse("{\"name\":\"outer\",\"steps\":[{\"routine\":\"nope\"}]}")), "no routine named nope");

        FakeHost loop = new FakeHost();
        loop.stored.put("loop", parse("{\"name\":\"loop\",\"steps\":[{\"routine\":\"loop\"}]}"));
        contains("a routine calling itself", loop.run(loop.stored.get("loop")), "more than " + RoutineRunner.MAX_CALL_DEPTH + " deep");
    }

    static void store() throws Exception {
        File dir = Files.createTempDirectory("routines").toFile();
        RoutineStore s = new RoutineStore(new File(dir, "r"), ACTIONS);
        eq("empty store", 0, s.names().size());
        s.save(new JSONObject("{\"name\":\"b\",\"steps\":[{\"wait\":1}]}"));
        s.save(new JSONObject("{\"name\":\"a\",\"description\":\"x\",\"steps\":[{\"head\":2}]}"));
        eq("listed sorted", Arrays.asList("a", "b"), s.names());
        eq("loads back", "x", s.load("a").description);
        check("exists", s.exists("a") && !s.exists("c") && !s.exists("../etc"));
        eq("missing loads as null", null, s.load("c"));
        try {
            s.save(new JSONObject("{\"name\":\"bad\",\"steps\":[{\"head\":999}]}"));
            check("bad routine refused", false);
        } catch (Routine.Invalid e) {
            check("bad routine not saved", !s.exists("bad"));
        }
        Files.write(new File(new File(dir, "r"), "broken.json").toPath(), "{not json".getBytes("UTF-8"));
        try {
            s.load("broken");
            check("broken file refused", false);
        } catch (Routine.Invalid e) {
            contains("broken file", e.getMessage(), "not valid JSON");
        }
        Files.write(new File(new File(dir, "r"), "other.json").toPath(), "{\"name\":\"a\",\"steps\":[{\"wait\":1}]}".getBytes("UTF-8"));
        try {
            s.load("other");
            check("file name and routine name must match", false);
        } catch (Routine.Invalid e) {
            contains("name mismatch", e.getMessage(), "holds routine");
        }
        check("delete", s.delete("a") && !s.exists("a") && !s.delete("a"));
    }

    /** The examples in docs/routines must all be valid. */
    static void examples(File dir) throws Exception {
        File[] files = dir.listFiles();
        check("examples found in " + dir, files != null && files.length > 0);
        if (files == null) return;
        Set<String> all = new HashSet<>(Routine.STEP_ACTIONS);
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            try {
                Routine r = Routine.parse(new JSONObject(text), all);
                eq(f.getName() + " name matches file", f.getName(), r.name + ".json");
            } catch (Exception e) {
                check(f.getName() + ": " + e.getMessage(), false);
            }
        }
    }
}
