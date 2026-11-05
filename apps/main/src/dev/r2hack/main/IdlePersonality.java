package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Idle personality (docs/liveliness.md): while R2 is awake and nothing else is going on, he plays a random
 * droid sound from a mood bank now and then, and sometimes looks around. How often depends on the
 * liveliness setting, which other features (sound reactions, activity presets) read too.
 * <p>
 * Brain calls {@link #tick} from its timer thread. This class only decides; the Host does the playing and
 * moving, so the logic runs off the robot in apps/test.sh. Chatter never counts as activity, so R2 still
 * falls asleep after the usual idle time.
 */
final class IdlePersonality {
    /** The liveliness levels, quietest first. "quiet" is the default. */
    static final List<String> LEVELS = Collections.unmodifiableList(Arrays.asList("off", "quiet", "chatty", "full"));
    static final String DEFAULT_LEVEL = "quiet";

    /** Head angles for a look around (the factory actions use up to 45 degrees either way). */
    private static final int[] LOOK_ANGLES = {-45, -30, -20, 20, 30, 45};
    /** How long R2 looks to the side before facing forward again. */
    static final long LOOK_MIN_MS = 1_200, LOOK_MAX_MS = 2_500;
    /** If a sound is still playing when chatter is due, try again this much later. */
    static final long RETRY_MS = 2_000;

    /** Factory sound numbers sorted by mood, used when the build has no assets/moods.json. */
    static final String DEFAULT_MOODS = "{\"chatty\":[1,5,6],\"happy\":[8,9,13],\"curious\":[12]}";

    interface Host {
        void playSound(int number);

        void head(int angle);
    }

    /** Timing per level: the wait between idle events, and the chance an event is a look around. */
    static final class Timing {
        final long minMs, maxMs;
        final int lookPercent;

        Timing(long minMs, long maxMs, int lookPercent) {
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.lookPercent = lookPercent;
        }
    }

    static Timing timing(String level) {
        switch (level) {
            case "quiet": return new Timing(60_000, 120_000, 0);
            case "chatty": return new Timing(25_000, 50_000, 35);
            case "full": return new Timing(10_000, 25_000, 50);
            default: return null; // off
        }
    }

    /** Lower case, and the default for anything that isn't a level. */
    static String normalize(String level) {
        String l = level == null ? "" : level.trim().toLowerCase(java.util.Locale.ROOT);
        return LEVELS.contains(l) ? l : DEFAULT_LEVEL;
    }

    static boolean isLevel(String level) {
        return level != null && LEVELS.contains(level.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Reads mood banks from {"mood": [sound numbers], ...}. Numbers the build can't play (not in
     * {@code playable}, if given) and empty moods are left out. Returns an empty map if nothing is left.
     */
    static Map<String, int[]> parseMoods(String json, java.util.Set<Integer> playable) {
        Map<String, int[]> moods = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<?> it = o.keys(); it.hasNext(); ) {
                String mood = (String) it.next();
                JSONArray a = o.optJSONArray(mood);
                if (a == null) continue;
                List<Integer> keep = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    int n = a.optInt(i, -1);
                    if (n >= 0 && (playable == null || playable.contains(n)) && !keep.contains(n)) keep.add(n);
                }
                if (keep.isEmpty()) continue;
                int[] arr = new int[keep.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = keep.get(i);
                moods.put(mood, arr);
            }
        } catch (Exception ignored) {
            // a broken file means no moods; Brain falls back to the defaults
        }
        return moods;
    }

    private final Host host;
    private final Random random;
    private final Map<String, int[]> moods;
    private final List<String> moodNames;

    private String level = DEFAULT_LEVEL;
    private boolean wasAllowed;
    private long nextAt;           // when the next idle event is due (0: not scheduled)
    private long lookBackAt;       // when to face forward again after a look (0: not looking)
    private int lastSound = -1;
    private String lastEvent = "";

    IdlePersonality(Host host, Random random, Map<String, int[]> moods) {
        this.host = host;
        this.random = random;
        this.moods = moods;
        this.moodNames = new ArrayList<>(moods.keySet());
    }

    synchronized String level() {
        return level;
    }

    /** Changes the level; the next event is scheduled afresh with the new timing. */
    synchronized void setLevel(String l) {
        String n = normalize(l);
        if (n.equals(level)) return;
        level = n;
        wasAllowed = false;
        nextAt = 0;
    }

    synchronized String status() {
        return "idle: " + level + (moods.isEmpty() ? ", no mood sounds" : ", moods " + moodNames)
                + (lastEvent.isEmpty() ? "" : ", last " + lastEvent);
    }

    /**
     * Called a few times a second.
     *
     * @param now          the time in ms
     * @param allowed      R2 is awake and idle: not running an action or routine, not driven, no music,
     *                     no voice conversation, no face being tracked
     * @param soundPlaying a sound is playing (it delays chatter, but not facing forward again)
     */
    synchronized void tick(long now, boolean allowed, boolean soundPlaying) {
        if (!allowed) {
            // Something else has the robot: forget any look (whatever runs owns the head now) and start
            // a fresh wait once it's over, so R2 doesn't chatter straight after a command.
            wasAllowed = false;
            lookBackAt = 0;
            return;
        }
        Timing t = timing(level);
        if (t == null) {
            if (lookBackAt != 0) faceForward();
            return;
        }
        if (!wasAllowed || nextAt == 0) {
            wasAllowed = true;
            nextAt = now + between(t.minMs, t.maxMs);
        }
        if (lookBackAt != 0 && now >= lookBackAt) faceForward();
        if (now < nextAt) return;
        if (lookBackAt != 0) return; // still looking: wait for that to finish
        boolean look = t.lookPercent > 0 && random.nextInt(100) < t.lookPercent;
        if (look) {
            int angle = LOOK_ANGLES[random.nextInt(LOOK_ANGLES.length)];
            host.head(angle);
            lookBackAt = now + between(LOOK_MIN_MS, LOOK_MAX_MS);
            lastEvent = "look " + angle;
            // Half the looks come with a sound, if one can play now.
            if (!soundPlaying && random.nextBoolean()) chatter(lastEvent + " + ");
        } else if (soundPlaying) {
            nextAt = now + RETRY_MS;
            return;
        } else if (!chatter("")) {
            lastEvent = "no sound to play";
        }
        nextAt = now + between(t.minMs, t.maxMs);
    }

    private void faceForward() {
        host.head(0);
        lookBackAt = 0;
    }

    /** Plays a random sound from a random mood, not the same sound twice in a row if there's a choice. */
    private boolean chatter(String prefix) {
        if (moodNames.isEmpty()) return false;
        String mood = moodNames.get(random.nextInt(moodNames.size()));
        int[] bank = moods.get(mood);
        if (bank.length == 1 && bank[0] == lastSound && moodNames.size() > 1) { // another mood, then
            int i = (moodNames.indexOf(mood) + 1 + random.nextInt(moodNames.size() - 1)) % moodNames.size();
            mood = moodNames.get(i);
            bank = moods.get(mood);
        }
        int n = bank[random.nextInt(bank.length)];
        if (n == lastSound && bank.length > 1) n = bank[(indexOf(bank, n) + 1 + random.nextInt(bank.length - 1)) % bank.length];
        lastSound = n;
        host.playSound(n);
        lastEvent = prefix + mood + " sound " + n;
        return true;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private long between(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }
}
