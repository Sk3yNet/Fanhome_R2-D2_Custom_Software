package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The soundboard (docs/soundboard.md): everything R2 can play on demand, in one list for the web page's
 * Soundboard tab, GET /sounds and the PC tools. Each entry carries the routine step that plays the same
 * thing, so whatever is on the board can be put in a routine.
 * <p>
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh). Brain does the playing.
 */
final class Soundboard {
    /** Names for the factory sounds whose use is known (Brain's S_* numbers); the rest are "Sound N". */
    static final Object[][] KNOWN_SOUNDS = {
            {0, "Who are you"}, {1, "Song"}, {3, "Pairing failed"}, {5, "Hm?"}, {6, "Noise"}, {7, "OK beep"},
            {8, "Wake up"}, {9, "Paired"}, {12, "Huh?"}, {13, "Saw a face"},
            {100, "Princess Leia clip"}, {101, "Luke Skywalker clip"},
    };

    /**
     * Built-in actions that are mostly a sound, in the board's order. Only the ones this build has are listed
     * and a routine step can run (whistle and freak-out arrived separately), so the board follows Brain.ACTIONS
     * and Routine.STEP_ACTIONS.
     */
    static final String[][] REACTIONS = {
            {"react-clap", "Hm? (a clap)"}, {"react-double-clap", "Happy (two claps)"}, {"react-loud", "Startled (a bang)"},
            {"whistle", "Wolf whistle"}, {"freak-out", "Freak out"}, {"noise", "Make some noise"},
            {"who-are-you", "Who are you"}, {"not-recognized", "Huh?"}, {"shake-head", "Shake head"},
    };

    /** Generated chirp set pieces that "random" never picks; listed when Chirp knows them. */
    static final String[] CHIRP_SPECIALS = {"whistle", "scream"};

    /** The mood-bank name that means "any bank". */
    static final String ANY = "random";

    private Soundboard() {}

    static String label(int number) {
        for (Object[] k : KNOWN_SOUNDS) if ((Integer) k[0] == number) return (String) k[1];
        return "Sound " + number;
    }

    /** The chirp moods this build can make: "random", Chirp.MOODS, then the set pieces it knows. */
    static List<String> chirpMoods() {
        List<String> out = new ArrayList<>();
        out.add("random");
        for (String m : Chirp.MOODS) out.add(m);
        for (String m : CHIRP_SPECIALS) if (Chirp.knownMood(m) && !out.contains(m)) out.add(m);
        return out;
    }

    /**
     * The board as JSON: {"sounds":[{number,label,moods,step}], "moods":[{name,sounds,step}],
     * "chirps":[{mood,step}], "reactions":[{action,label,custom,step}], "beeps":{max,step}}.
     *
     * @param playable factory sound numbers this build can play (Sounds.numbers())
     * @param moods    mood banks (IdlePersonality's, from assets/moods.json or the defaults)
     * @param actions  the built-in actions this build has (Brain.ACTIONS)
     * @param routines stored routine names: a routine named like a reaction replaces it ("custom")
     */
    static JSONObject catalogue(Set<Integer> playable, Map<String, int[]> moods, Collection<String> actions,
                                Collection<String> routines) throws Exception {
        JSONArray sounds = new JSONArray();
        for (int n : new TreeSet<>(playable)) {
            JSONArray in = new JSONArray();
            for (Map.Entry<String, int[]> m : moods.entrySet()) {
                for (int x : m.getValue()) {
                    if (x == n) {
                        in.put(m.getKey());
                        break;
                    }
                }
            }
            sounds.put(new JSONObject().put("number", n).put("label", label(n)).put("moods", in)
                    .put("step", new JSONObject().put("sound", n)));
        }
        JSONArray banks = new JSONArray();
        for (Map.Entry<String, int[]> m : moods.entrySet()) {
            JSONArray nums = new JSONArray();
            for (int x : m.getValue()) nums.put(x);
            banks.put(new JSONObject().put("name", m.getKey()).put("sounds", nums)
                    .put("step", new JSONObject().put("sound", m.getKey())));
        }
        if (!moods.isEmpty() && !moods.containsKey(ANY)) {
            banks.put(new JSONObject().put("name", ANY).put("sounds", new JSONArray())
                    .put("step", new JSONObject().put("sound", ANY)));
        }
        JSONArray chirps = new JSONArray();
        for (String m : chirpMoods()) chirps.put(new JSONObject().put("mood", m).put("step", new JSONObject().put("chirp", m)));
        JSONArray reactions = new JSONArray();
        for (String[] r : REACTIONS) {
            if (!actions.contains(r[0]) || !Routine.STEP_ACTIONS.contains(r[0])) continue;
            reactions.put(new JSONObject().put("action", r[0]).put("label", r[1])
                    .put("custom", routines.contains(r[0]))
                    .put("step", new JSONObject().put("action", r[0])));
        }
        return new JSONObject().put("sounds", sounds).put("moods", banks).put("chirps", chirps).put("reactions", reactions)
                .put("beeps", new JSONObject().put("max", Routine.MAX_BEEPS_TEXT)
                        .put("step", new JSONObject().put("beeps", "Hello there!")));
    }

    /**
     * A random sound from a mood bank ("random": from all banks together), not the same as last when there
     * is a choice. Returns -1 for an unknown or empty bank.
     */
    static int pick(Map<String, int[]> moods, String mood, Random random, int last) {
        List<Integer> bank = new ArrayList<>();
        if (ANY.equals(mood) && !moods.containsKey(ANY)) {
            for (int[] b : moods.values()) for (int x : b) if (!bank.contains(x)) bank.add(x);
        } else {
            int[] b = moods.get(mood);
            if (b != null) for (int x : b) bank.add(x);
        }
        if (bank.isEmpty()) return -1;
        if (bank.size() > 1) bank.remove(Integer.valueOf(last));
        return bank.get(random.nextInt(bank.size()));
    }
}
