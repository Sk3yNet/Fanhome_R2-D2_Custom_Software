package dev.r2hack.main;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Off-robot tests for places, route recording and trip planning (apps/test.sh). */
public final class PlacesTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        parsing();
        recorder();
        planning();
        tripRoutine();
        tourAndPatrol();
        store();
        System.out.println("places: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void parsing() throws Exception {
        Places.Place p = Places.parsePlace(new JSONObject("{\"name\":\"living-room\",\"there\":[{\"power\":30,\"angle\":0,\"seconds\":2.5}]}"));
        eq("default spoken", Arrays.asList("the living room"), p.spoken);
        eq("no back", 0, p.back.size());
        eq("segment", "30@0 2500ms", p.there.get(0).toString());
        Places.Place q = Places.parsePlace(new JSONObject("{\"name\":\"kitchen\",\"spoken\":[\"The  Kitchen\",\"cooking room\"],"
                + "\"area\":\"Kitchen\",\"there\":[{\"power\":30,\"seconds\":1}],\"back\":[{\"power\":20,\"angle\":-90,\"seconds\":1}]}"));
        eq("spoken normalized", Arrays.asList("the kitchen", "cooking room"), q.spoken);
        eq("area", "Kitchen", q.area);
        contains("home reserved", invalid("{\"name\":\"home\",\"there\":[{\"power\":30,\"seconds\":1}]}"), "not \"home\"");
        contains("needs there", invalid("{\"name\":\"a\"}"), "there must be a list");
        contains("power cap", invalid("{\"name\":\"a\",\"there\":[{\"power\":90,\"seconds\":1}]}"), "outside 0 to 60");
        contains("angle range", invalid("{\"name\":\"a\",\"there\":[{\"power\":30,\"angle\":270,\"seconds\":1}]}"), "outside -180 to 180");
        contains("spoken words", invalid("{\"name\":\"a\",\"spoken\":[\"room 2\"],\"there\":[{\"power\":30,\"seconds\":1}]}"), "a-z only");
        contains("unknown key", invalid("{\"name\":\"a\",\"x\":1,\"there\":[{\"power\":30,\"seconds\":1}]}"), "unknown key");
        // Round trip through JSON.
        Places.Place r = Places.parsePlace(q.json());
        eq("round trip", q.json().toString(), r.json().toString());
    }

    static void recorder() {
        Places.Recorder rec = new Places.Recorder();
        // Joystick held steady forward for 2 s (repeats every 250 ms, with a little wobble), then turned right, then let go.
        long t = 1000;
        for (int i = 0; i < 8; i++, t += 250) rec.move(t, i % 2 == 0 ? 29 : 31, i % 2 == 0 ? 1 : -1);
        for (int i = 0; i < 4; i++, t += 250) rec.move(t, 20, 90);
        rec.move(t, 0, 0); // released
        rec.move(t + 5000, 30, 180); // later: back, then the joystick page vanished (no stop)
        List<Places.Segment> segs = rec.finish(t + 20000);
        eq("segments", "[30@0 2000ms, 20@90 1000ms, 30@180 3000ms]", segs.toString());
        Places.Recorder big = new Places.Recorder();
        eq("left spin kept negative", "[20@-90 500ms]", rec2(big, -90).toString());
        Places.Recorder r270 = new Places.Recorder();
        eq("270 becomes -90", "[20@-90 500ms]", rec2(r270, 270).toString());
    }

    private static List<Places.Segment> rec2(Places.Recorder r, int angle) {
        r.move(0, 20, angle);
        r.move(500, 0, 0);
        return r.finish(1000);
    }

    static Map<String, Places.Place> house() throws Exception {
        Map<String, Places.Place> m = new LinkedHashMap<>();
        m.put("kitchen", Places.parsePlace(new JSONObject("{\"name\":\"kitchen\",\"there\":[{\"power\":30,\"seconds\":2},{\"power\":20,\"angle\":90,\"seconds\":1},{\"power\":30,\"angle\":20,\"seconds\":1}]}")));
        m.put("office", Places.parsePlace(new JSONObject("{\"name\":\"office\",\"there\":[{\"power\":30,\"seconds\":3}],\"back\":[{\"power\":30,\"angle\":180,\"seconds\":3}]}")));
        return m;
    }

    static void planning() throws Exception {
        Map<String, Places.Place> h = house();
        List<Places.Segment> none = new ArrayList<>();
        List<Places.Segment> spin = Arrays.asList(new Places.Segment(30, 90, 2000));
        contains("unknown start", Places.plan("unknown", "kitchen", h, none).error, "don't know where I am");
        contains("no such place", Places.plan("home", "garage", h, none).error, "no place named garage");
        eq("home to kitchen", h.get("kitchen").there.toString(), Places.plan("home", "kitchen", h, none).segments.toString());
        eq("already there", 0, Places.plan("office", "office", h, none).segments.size());
        eq("taught way back", "[30@180 3000ms]", Places.plan("office", "home", h, none).segments.toString());
        contains("no way back", Places.plan("kitchen", "home", h, none).error, "way back from kitchen");
        // Mirrored: turn around, the legs in reverse order with left and right swapped, turn around again.
        eq("mirrored way back", "[30@90 2000ms, 30@-20 1000ms, 20@-90 1000ms, 30@0 2000ms, 30@90 2000ms]",
                Places.plan("kitchen", "home", h, spin).segments.toString());
        Places.Trip t = Places.plan("office", "kitchen", h, none);
        eq("chained via home", "[30@180 3000ms, 30@0 2000ms, 20@90 1000ms, 30@20 1000ms]", t.segments.toString());
        eq("chained desc", "office -> home -> kitchen", t.description);
        eq("chained end", "kitchen", t.end);
        eq("mirror 180", 180, new Places.Segment(30, -180, 100).mirrored().angle);
    }

    static void tripRoutine() throws Exception {
        Places.Trip t = Places.plan("home", "office", house(), new ArrayList<Places.Segment>());
        Routine r = Places.trip("office", t);
        RoutineTest.FakeHost h = new RoutineTest.FakeHost();
        Places.Trip two = new Places.Trip(Arrays.asList(new Places.Segment(30, 0, 1000), new Places.Segment(20, 90, 600)), null, "x", "office");
        eq("trip runs", "finished", h.run(Places.trip("office", two)));
        // A chirp, the warning pause, the drives flowing into each other (no stop between), a stop, the arrival sound.
        eq("trip log", Arrays.asList(
                "0 sound 7",
                "1500 move angle=0 power=30",
                "2000 move angle=0 power=30",
                "2500 move angle=90 power=20",
                "3000 move angle=90 power=20",
                "3100 move angle=0 power=0",
                "3100 sound 5"), h.log);
        eq("timeout", 1500 + 3000 + 750 + 10000L, r.timeoutMs);
        eq("trip doesn't count as a stored routine move check", true, Places.moves(r));
        // Charging stops a trip: the controller ignores driving then, so it can't be on the charger.
        RoutineTest.FakeHost c = new RoutineTest.FakeHost();
        c.fields.put("charging", 1);
        contains("on charger", c.run(r), "on the charger");
    }

    static void tourAndPatrol() throws Exception {
        Map<String, Places.Place> h = house();
        List<Places.Segment> spin = Arrays.asList(new Places.Segment(30, 90, 2000));
        Places.Trip t = Places.tour("home", h, spin);
        eq("tour desc", "tour: kitchen, office, home", t.description);
        eq("tour end", "home", t.end);
        // kitchen (4 s) + pause 3 + kitchen back mirrored (8 s) + office (3) + pause 3 + office back (3)
        eq("tour seconds", 24.0, Places.seconds(t.segments));
        Places.Trip noWay = Places.tour("home", h, new ArrayList<Places.Segment>());
        eq("tour stops where it can't return", "tour: kitchen, ends at kitchen", noWay.description);
        eq("tour from unknown", true, Places.tour("unknown", h, spin).error != null);
        eq("tour with no places", true, Places.tour("home", new LinkedHashMap<String, Places.Place>(), spin).error != null);
        // What counts as moving, for "R2 no longer knows where he is".
        Routine lights = Routine.parse(new JSONObject("{\"name\":\"l\",\"steps\":[{\"led\":{\"r\":2}},{\"head\":30},{\"sound\":3}]}"), RoutineTest.actions());
        eq("lights don't move", false, Places.moves(lights));
        Routine spinR = Routine.parse(new JSONObject("{\"name\":\"s\",\"steps\":[{\"repeat\":2,\"steps\":[{\"mode\":3}]}]}"), RoutineTest.actions());
        eq("turns move", true, Places.moves(spinR));
        Routine dance = Routine.parse(new JSONObject("{\"name\":\"d\",\"steps\":[{\"action\":\"dance\"}]}"), RoutineTest.actions());
        eq("dance moves", true, Places.moves(dance));
        Routine left = Routine.parse(new JSONObject("{\"name\":\"l\",\"steps\":[{\"drive\":{\"power\":20,\"angle\":-90,\"seconds\":1}}]}"), RoutineTest.actions());
        eq("routine left spin allowed", -90, left.steps.get(0).angle);
        Map<String, String> ph = Places.phrases(h.values());
        eq("phrases", "{go home=goto:home, come home=goto:home, go to the kitchen=goto:kitchen, go to the office=goto:office}", ph.toString());
    }

    static void store() throws Exception {
        File dir = Files.createTempDirectory("r2places").toFile();
        File f = new File(dir, "places.json");
        Places.Store s = new Places.Store(f);
        eq("starts unknown", "unknown", s.location());
        for (Places.Place p : house().values()) s.put(p);
        s.setLocation("home");
        s.setTurnAround(Arrays.asList(new Places.Segment(30, 90, 2000)));
        Places.Store again = new Places.Store(f);
        eq("names kept", Arrays.asList("kitchen", "office"), again.names());
        eq("location kept", "home", again.location());
        eq("turn kept", 1, again.turnAround().size());
        again.setLocation("office");
        again.delete("office");
        eq("deleting where R2 is", "unknown", again.location());
        eq("bad location", false, again.setLocation("garage"));
        Places.Place dup = Places.parsePlace(new JSONObject("{\"name\":\"pantry\",\"spoken\":[\"the kitchen\"],\"there\":[{\"power\":30,\"seconds\":1}]}"));
        String err = null;
        try {
            again.put(dup);
        } catch (Routine.Invalid e) {
            err = e.getMessage();
        }
        contains("spoken clash", err, "already a name of kitchen");
        eq("not added", false, again.names().contains("pantry"));
    }

    private static String invalid(String json) {
        try {
            Places.parsePlace(new JSONObject(json));
            return "(parsed)";
        } catch (Routine.Invalid e) {
            return e.getMessage();
        } catch (Exception e) {
            return "(json) " + e;
        }
    }

    static void eq(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static void contains(String name, String got, String part) {
        if (got != null && got.contains(part)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want something with: " + part + "\n  got:  " + got);
        }
    }
}
