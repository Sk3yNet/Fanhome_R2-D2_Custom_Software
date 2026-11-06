package dev.r2hack.main;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Off-robot tests for the soundboard (Soundboard): the catalogue the web page and GET /sounds show, and
 * that every entry's routine step is one the routine engine accepts. Run with apps/test.sh.
 */
public final class SoundboardTest {
    private static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        catalogue();
        picking();
        System.out.println("soundboard: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Map<String, int[]> moods() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("chatty", new int[] {1, 5, 6});
        m.put("happy", new int[] {8, 9, 13});
        m.put("curious", new int[] {12});
        return m;
    }

    static void catalogue() throws Exception {
        Set<Integer> playable = new HashSet<>(Arrays.asList(13, 0, 1, 5, 6, 7, 8, 9, 12, 42, 100));
        // Brain needs Android, so its ACTIONS are stood in for by the built-ins a routine step can run.
        List<String> actions = new java.util.ArrayList<>(Routine.STEP_ACTIONS);
        JSONObject c = Soundboard.catalogue(playable, moods(), actions, Collections.singletonList("react-loud"));

        JSONArray sounds = c.getJSONArray("sounds");
        check("every playable sound listed", sounds.length() == playable.size());
        check("sounds in number order", sounds.getJSONObject(0).getInt("number") == 0
                && sounds.getJSONObject(sounds.length() - 1).getInt("number") == 100);
        JSONObject eight = null, unknown = null;
        for (int i = 0; i < sounds.length(); i++) {
            JSONObject s = sounds.getJSONObject(i);
            if (s.getInt("number") == 8) eight = s;
            if (s.getInt("number") == 42) unknown = s;
        }
        check("known sound named", eight != null && "Wake up".equals(eight.getString("label")));
        check("known sound's mood", eight != null && eight.getJSONArray("moods").length() == 1
                && "happy".equals(eight.getJSONArray("moods").getString(0)));
        check("unknown sound numbered", unknown != null && "Sound 42".equals(unknown.getString("label")));

        JSONArray banks = c.getJSONArray("moods");
        check("mood banks plus random", banks.length() == 4 && "random".equals(banks.getJSONObject(3).getString("name")));

        JSONArray chirps = c.getJSONArray("chirps");
        check("chirps start with random", "random".equals(chirps.getJSONObject(0).getString("mood")));
        check("chirps include every mood", chirps.length() >= 1 + Chirp.MOODS.length);
        for (int i = 0; i < chirps.length(); i++) {
            check("chirp " + chirps.getJSONObject(i).getString("mood") + " is known", Chirp.knownMood(chirps.getJSONObject(i).getString("mood")));
        }

        JSONArray reactions = c.getJSONArray("reactions");
        check("reactions listed", reactions.length() >= 3);
        boolean customLoud = false, clapNotCustom = false;
        for (int i = 0; i < reactions.length(); i++) {
            JSONObject r = reactions.getJSONObject(i);
            check("reaction " + r.getString("action") + " is a built-in action", actions.contains(r.getString("action")));
            if ("react-loud".equals(r.getString("action"))) customLoud = r.getBoolean("custom");
            if ("react-clap".equals(r.getString("action"))) clapNotCustom = !r.getBoolean("custom");
        }
        check("a routine replacing a reaction is marked custom", customLoud && clapNotCustom);
        JSONObject only = Soundboard.catalogue(playable, moods(), Arrays.asList("noise", "chirp", "react-clap"), Collections.<String>emptyList());
        check("only the actions this build has, that routines can run", only.getJSONArray("reactions").length() == 2);

        // Everything on the board goes into a routine as is: one routine with every entry's step must parse.
        JSONArray steps = new JSONArray();
        for (String list : new String[] {"sounds", "moods", "chirps", "reactions"}) {
            JSONArray a = c.getJSONArray(list);
            for (int i = 0; i < a.length(); i++) steps.put(a.getJSONObject(i).getJSONObject("step"));
        }
        steps.put(c.getJSONObject("beeps").getJSONObject("step"));
        try {
            Routine r = Routine.parse(new JSONObject().put("name", "everything").put("steps", steps), Routine.STEP_ACTIONS);
            check("every board entry is a routine step", r.stepCount() == steps.length());
        } catch (Routine.Invalid e) {
            check("every board entry is a routine step: " + e.getMessage(), false);
        }

        JSONObject none = Soundboard.catalogue(Collections.<Integer>emptySet(), new LinkedHashMap<String, int[]>(), actions,
                Collections.<String>emptyList());
        check("no sounds.json: no factory sounds, no banks", none.getJSONArray("sounds").length() == 0
                && none.getJSONArray("moods").length() == 0);
        check("no sounds.json: chirps still there", none.getJSONArray("chirps").length() > 0);
    }

    static void picking() {
        Map<String, int[]> m = moods();
        Random r = new Random(1);
        int last = -1;
        for (int i = 0; i < 200; i++) {
            int n = Soundboard.pick(m, "happy", r, last);
            check("happy pick " + n, n == 8 || n == 9 || n == 13);
            check("no repeat", n != last);
            last = n;
        }
        check("single-sound bank repeats", Soundboard.pick(m, "curious", r, 12) == 12);
        check("unknown bank", Soundboard.pick(m, "grumpy", r, -1) == -1);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(Soundboard.pick(m, "random", r, -1));
        check("random uses every bank", seen.equals(new HashSet<>(Arrays.asList(1, 5, 6, 8, 9, 13, 12))));
        check("random with no banks", Soundboard.pick(new LinkedHashMap<String, int[]>(), "random", r, -1) == -1);
    }
}
