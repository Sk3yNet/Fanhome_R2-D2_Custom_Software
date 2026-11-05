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
        triggers();
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

    static String triggerInvalid(String triggers) {
        return invalid("{\"name\":\"t\",\"steps\":[{\"wait\":1}],\"triggers\":" + triggers + "}");
    }

    static Triggers.Trigger trigger(String json) throws Exception {
        return parse("{\"name\":\"t\",\"steps\":[{\"wait\":1}],\"triggers\":[" + json + "]}").triggers.get(0);
    }

    static java.util.Calendar at(int weekday, int h, int m) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.NOVEMBER, 1 + weekday, h, m, 0); // 1 Nov 2026 is a Sunday
        return c;
    }

    static void triggers() throws Exception {
        // parsing
        Routine r = parse("{\"name\":\"t\",\"steps\":[{\"wait\":1}],\"triggers\":["
                + "{\"button\":4},{\"voice\":\"Light  Show\"},{\"schedule\":{\"at\":\"7:30\",\"days\":[\"mon\",\"FRI\"]}},"
                + "{\"schedule\":{\"every\":15,\"from\":\"22:00\",\"to\":\"06:00\"},\"if_busy\":\"replace\"},"
                + "{\"event\":\"doorbell\"},{\"mqtt\":{\"topic\":\"zigbee2mqtt/+/door\",\"field\":\"contact\",\"value\":false}}]}");
        eq("trigger count", 6, r.triggers.size());
        eq("phrase normalized", "light show", r.triggers.get(1).text);
        eq("describe schedule", "at 07:30 on mon, fri (if idle)", Triggers.describe(r.triggers.get(2)));
        eq("describe window", "every 15 min from 22:00 to 06:00", Triggers.describe(r.triggers.get(3)));
        check("buttons and voice replace", r.triggers.get(0).replace && r.triggers.get(1).replace);
        check("events skip if busy by default", !r.triggers.get(4).replace);
        eq("no triggers", 0, parse("{\"name\":\"n\",\"steps\":[{\"wait\":1}]}").triggers.size());
        eq("R2-D2 spelled out", "hey r two d two", Triggers.normalizePhrase("Hey R2-D2"));

        contains("power button", triggerInvalid("[{\"button\":1}]"), "can't be taken over");
        contains("pairing button", triggerInvalid("[{\"button\":3}]"), "can't be taken over");
        contains("one-word phrase", triggerInvalid("[{\"voice\":\"party\"}]"), "2 to 6 plain words");
        contains("digits in phrase", triggerInvalid("[{\"voice\":\"party 2\"}]"), "2 to 6 plain words");
        contains("built-in phrase", triggerInvalid("[{\"voice\":\"turn left\"}]"), "overlaps the built-in");
        contains("phrase starting with a built-in", triggerInvalid("[{\"voice\":\"stop the music\"}]"), "overlaps the built-in");
        contains("same button twice", triggerInvalid("[{\"button\":5},{\"button\":5}]"), "listed twice");
        contains("at and every", triggerInvalid("[{\"schedule\":{\"at\":\"07:00\",\"every\":10}}]"), "one of them");
        contains("bad time", triggerInvalid("[{\"schedule\":{\"at\":\"24:00\"}}]"), "not a time of day");
        contains("every too often", triggerInvalid("[{\"schedule\":{\"every\":1}}]"), "from 5 to 1440");
        contains("bad day", triggerInvalid("[{\"schedule\":{\"at\":\"07:00\",\"days\":[\"monday\"]}}]"), "one of sun");
        contains("from without to", triggerInvalid("[{\"schedule\":{\"every\":10,\"from\":\"07:00\"}}]"), "go together");
        contains("bad event", triggerInvalid("[{\"event\":\"Door Bell\"}]"), "a-z, 0-9");
        contains("if_busy value", triggerInvalid("[{\"event\":\"x\",\"if_busy\":\"queue\"}]"), "\"skip\" (default) or \"replace\"");
        contains("if_busy on a button", triggerInvalid("[{\"button\":4,\"if_busy\":\"skip\"}]"), "unknown key");
        contains("mqtt everything", triggerInvalid("[{\"mqtt\":{\"topic\":\"#\"}}]"), "first level");
        contains("mqtt $SYS", triggerInvalid("[{\"mqtt\":{\"topic\":\"$SYS/broker\"}}]"), "first level");
        contains("mqtt bad wildcard", triggerInvalid("[{\"mqtt\":{\"topic\":\"a/b#\"}}]"), "whole level");
        contains("mqtt # in the middle", triggerInvalid("[{\"mqtt\":{\"topic\":\"a/#/b\"}}]"), "last level");
        contains("mqtt payload and field", triggerInvalid("[{\"mqtt\":{\"topic\":\"a\",\"payload\":\"ON\",\"field\":\"x\",\"value\":1}}]"), "not both");
        contains("unknown trigger", triggerInvalid("[{\"timer\":5}]"), "unknown trigger");
        contains("triggers not a list", triggerInvalid("{\"button\":4}"), "must be a list");

        // MQTT topic and payload matching
        check("+ matches one level", Triggers.topicMatches("z/+/door", "z/hall/door") && !Triggers.topicMatches("z/+/door", "z/a/b/door"));
        check("# matches the rest", Triggers.topicMatches("z/#", "z/a/b") && Triggers.topicMatches("z/#", "z"));
        check("exact topic", Triggers.topicMatches("a/b", "a/b") && !Triggers.topicMatches("a/b", "a/b/c") && !Triggers.topicMatches("a/b/c", "a/b"));
        Triggers.Trigger door = trigger("{\"mqtt\":{\"topic\":\"z/door\",\"field\":\"contact\",\"value\":false}}");
        check("field matches", Triggers.payloadMatches(door, "{\"contact\":false,\"battery\":90}"));
        check("field differs", !Triggers.payloadMatches(door, "{\"contact\":true}"));
        check("not JSON", !Triggers.payloadMatches(door, "open"));
        Triggers.Trigger num = trigger("{\"mqtt\":{\"topic\":\"z\",\"field\":\"n\",\"value\":1}}");
        check("numbers compare as numbers", Triggers.payloadMatches(num, "{\"n\":1.0}"));
        check("payload match trims", Triggers.payloadMatches(trigger("{\"mqtt\":{\"topic\":\"z\",\"payload\":\"ON\"}}"), " ON\n"));

        // fire when a live message starts matching; retained ones only set the baseline
        Map<String, List<Triggers.Trigger>> m = new HashMap<>();
        m.put("door", Arrays.asList(door));
        m.put("bell", Arrays.asList(trigger("{\"mqtt\":{\"topic\":\"bell\",\"every_message\":true}}")));
        Triggers.Table table = new Triggers.Table(m);
        Triggers.MqttWatch w = new Triggers.MqttWatch();
        eq("retained match doesn't fire", 0, w.onMessage(table, "z/door", "{\"contact\":false}", true).size());
        eq("same state again doesn't fire", 0, w.onMessage(table, "z/door", "{\"contact\":false}", false).size());
        w.onMessage(table, "z/door", "{\"contact\":true}", false);
        List<Triggers.Hit> hits = w.onMessage(table, "z/door", "{\"contact\":false}", false);
        eq("door opened fires", 1, hits.size());
        eq("door routine", "door", hits.isEmpty() ? null : hits.get(0).routine);
        eq("first live match fires", 1, new Triggers.MqttWatch().onMessage(table, "z/door", "{\"contact\":false}", false).size());
        eq("every_message fires each time", 1, w.onMessage(table, "bell", "x", false).size());
        eq("every_message fires again", 1, w.onMessage(table, "bell", "x", false).size());
        eq("other topic", 0, w.onMessage(table, "other", "x", false).size());
        eq("topics to subscribe", new java.util.HashSet<>(Arrays.asList("z/door", "bell")), table.mqttTopics());

        // schedules
        Triggers.Trigger wk = trigger("{\"schedule\":{\"at\":\"07:30\",\"days\":[\"mon\"]}}");
        check("at: due on monday 07:30", Triggers.dueAt(wk, 7 * 60 + 30, 1));
        check("at: not tuesday", !Triggers.dueAt(wk, 7 * 60 + 30, 2));
        check("at: not 07:31", !Triggers.dueAt(wk, 7 * 60 + 31, 1));
        Triggers.Trigger ev = trigger("{\"schedule\":{\"every\":30}}");
        check("every: on the half hour", Triggers.dueAt(ev, 600, 3) && Triggers.dueAt(ev, 630, 3) && !Triggers.dueAt(ev, 615, 3));
        Triggers.Trigger day = trigger("{\"schedule\":{\"every\":60,\"from\":\"09:15\",\"to\":\"17:15\"}}");
        check("window: from its start", Triggers.dueAt(day, 9 * 60 + 15, 0) && Triggers.dueAt(day, 10 * 60 + 15, 0));
        check("window: up to its end", Triggers.dueAt(day, 17 * 60 + 15, 0) && !Triggers.dueAt(day, 18 * 60 + 15, 0));
        check("window: not before", !Triggers.dueAt(day, 8 * 60 + 15, 0) && !Triggers.dueAt(day, 10 * 60, 0));
        Triggers.Trigger night = trigger("{\"schedule\":{\"every\":120,\"from\":\"22:00\",\"to\":\"02:00\",\"days\":[\"fri\"]}}");
        check("overnight: friday 22:00", Triggers.dueAt(night, 22 * 60, 5));
        check("overnight: saturday 00:00 belongs to friday", Triggers.dueAt(night, 0, 6));
        check("overnight: not friday 00:00", !Triggers.dueAt(night, 0, 5));
        check("overnight: not in the day", !Triggers.dueAt(night, 12 * 60, 5));
        Map<String, List<Triggers.Trigger>> sm = new HashMap<>();
        sm.put("morning", Arrays.asList(wk));
        Triggers.Table st = new Triggers.Table(sm);
        eq("table due monday", 1, st.due(at(1, 7, 30)).size());
        eq("table not due sunday", 0, st.due(at(0, 7, 30)).size());
        check("clock not set in 1970", !Triggers.clockSet(0) && Triggers.clockSet(at(1, 0, 0).getTimeInMillis()));

        // events and the store: buttons and phrases belong to one routine
        File dir = Files.createTempDirectory("triggers").toFile();
        RoutineStore s = new RoutineStore(new File(dir, "r"), ACTIONS);
        int[] changes = {0};
        s.onChange = () -> changes[0]++;
        s.save(new JSONObject("{\"name\":\"a\",\"steps\":[{\"wait\":1}],\"triggers\":[{\"button\":4},{\"voice\":\"light show\"},{\"event\":\"doorbell\"}]}"));
        s.save(new JSONObject("{\"name\":\"b\",\"steps\":[{\"wait\":1}],\"triggers\":[{\"event\":\"doorbell\",\"if_busy\":\"replace\"}]}"));
        eq("change callbacks", 2, changes[0]);
        eq("button owner", "a", s.triggers().button(4));
        eq("no owner", null, s.triggers().button(5));
        eq("phrase owner", "a", s.triggers().phrases().get("light show"));
        eq("event: both routines", 2, s.triggers().event("doorbell").size());
        eq("event: first by name", "a", s.triggers().event("doorbell").get(0).routine);
        eq("unknown event", 0, s.triggers().event("nope").size());
        try {
            s.save(new JSONObject("{\"name\":\"c\",\"steps\":[{\"wait\":1}],\"triggers\":[{\"button\":4}]}"));
            check("button taken refused", false);
        } catch (Routine.Invalid e) {
            contains("button taken", e.getMessage(), "already starts routine a");
        }
        try {
            s.save(new JSONObject("{\"name\":\"c\",\"steps\":[{\"wait\":1}],\"triggers\":[{\"voice\":\"Light show\"}]}"));
            check("phrase taken refused", false);
        } catch (Routine.Invalid e) {
            contains("phrase taken", e.getMessage(), "already starts routine a");
        }
        s.save(new JSONObject("{\"name\":\"a\",\"steps\":[{\"wait\":1}],\"triggers\":[{\"button\":4}]}"));
        eq("a routine may keep its own button", "a", s.triggers().button(4));
        eq("phrase gone after replacing", null, s.triggers().phrases().get("light show"));
        s.delete("a");
        eq("button free after delete", null, s.triggers().button(4));
        Files.write(new File(new File(dir, "r"), "bad.json").toPath(),
                "{\"name\":\"bad\",\"steps\":[{\"head\":999}],\"triggers\":[{\"button\":5}]}".getBytes("UTF-8"));
        s.delete("b"); // rebuilds the table
        eq("a broken routine has no triggers", null, s.triggers().button(5));
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
