package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Off-robot tests for IdlePersonality and the liveliness levels. Run with apps/test.sh. */
public final class IdleTest {
    private static int passed, failed;

    static final class FakeHost implements IdlePersonality.Host {
        final List<String> log = new ArrayList<>();
        long now;

        public void playSound(int n) { log.add(now + " sound " + n); }

        public void head(int a) { log.add(now + " head " + a); }

        int count(String what) {
            int c = 0;
            for (String s : log) if (s.contains(what)) c++;
            return c;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static Map<String, int[]> defaults() {
        return IdlePersonality.parseMoods(IdlePersonality.DEFAULT_MOODS, null);
    }

    /** Runs ticks every 250 ms for the given time, like Brain's timer thread. */
    private static void run(IdlePersonality p, FakeHost h, long ms, boolean allowed) {
        for (long end = h.now + ms; h.now < end; h.now += 250) p.tick(h.now, allowed, false);
    }

    public static void main(String[] args) {
        // Levels
        check("default level", IdlePersonality.normalize(null).equals("quiet"));
        check("normalize case", IdlePersonality.normalize(" Chatty ").equals("chatty"));
        check("unknown -> default", IdlePersonality.normalize("loud").equals("quiet"));
        check("isLevel", IdlePersonality.isLevel("FULL") && !IdlePersonality.isLevel("loud"));
        check("off has no timing", IdlePersonality.timing("off") == null);

        // Moods
        Map<String, int[]> m = IdlePersonality.parseMoods("{\"a\":[1,2,2,99],\"b\":[99],\"c\":\"x\",\"d\":[]}",
                new HashSet<>(Arrays.asList(1, 2)));
        check("unplayable and empty moods dropped", m.size() == 1 && Arrays.equals(m.get("a"), new int[] {1, 2}));
        check("broken json -> no moods", IdlePersonality.parseMoods("{nope", null).isEmpty());
        check("defaults parse", defaults().size() == 3);

        // Off: nothing ever happens
        FakeHost h = new FakeHost();
        IdlePersonality p = new IdlePersonality(h, new Random(1), defaults());
        p.setLevel("off");
        run(p, h, 30 * 60_000, true);
        check("off is silent", h.log.isEmpty());

        // Quiet: sounds only, no head moves, 60-120 s apart
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(2), defaults());
        p.setLevel("quiet");
        run(p, h, 59_000, true);
        check("quiet waits at least a minute", h.log.isEmpty());
        run(p, h, 30 * 60_000, true);
        int quietSounds = h.count("sound");
        check("quiet plays some sounds (" + quietSounds + ")", quietSounds >= 14 && quietSounds <= 31);
        check("quiet never moves the head", h.count("head") == 0);

        // Full: more often, and looks around, always facing forward again
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(3), defaults());
        p.setLevel("full");
        run(p, h, 30 * 60_000, true);
        int looks = 0, backs = 0;
        for (String s : h.log) {
            if (s.endsWith("head 0")) backs++;
            else if (s.contains("head")) looks++;
        }
        check("full is busier than quiet", h.log.size() > 3 * quietSounds);
        check("full looks around", looks > 10);
        check("every look faces forward again", looks == backs);
        boolean repeat = false;
        String prev = null;
        for (String s : h.log) {
            if (!s.contains("sound")) continue;
            String n = s.substring(s.indexOf("sound"));
            if (n.equals(prev)) repeat = true;
            prev = n;
        }
        check("never the same sound twice in a row", !repeat);

        // Not allowed (an action, music, a conversation...): nothing, and a fresh wait afterwards
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(4), defaults());
        p.setLevel("full");
        run(p, h, 10 * 60_000, false);
        check("busy robot gets no chatter", h.log.isEmpty());
        run(p, h, 9_750, true);
        check("fresh wait after being busy", h.log.isEmpty());

        // Busy in the middle of a look: the head is left to whatever runs, no late "head 0"
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(5), defaults());
        p.setLevel("full");
        int guard = 0;
        while (h.count("head") == 0 && guard++ < 100_000) {
            p.tick(h.now, true, false);
            h.now += 250;
        }
        int before = h.log.size();
        p.tick(h.now, false, false);
        h.now += 250;
        p.tick(h.now, false, false);
        check("no face-forward once something else runs", h.log.size() == before);

        // A sound already playing delays chatter
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(6), defaults());
        p.setLevel("quiet");
        for (long end = h.now + 10 * 60_000; h.now < end; h.now += 250) p.tick(h.now, true, true);
        check("no chatter over a playing sound", h.count("sound") == 0);

        // Switching to off while looking faces forward
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(7), defaults());
        p.setLevel("full");
        guard = 0;
        while (h.count("head") == 0 && guard++ < 100_000) {
            p.tick(h.now, true, false);
            h.now += 250;
        }
        p.setLevel("off");
        p.tick(h.now, true, false);
        check("off while looking faces forward", h.log.get(h.log.size() - 1).endsWith("head 0"));

        // No moods: no sounds, but no crash
        h = new FakeHost();
        p = new IdlePersonality(h, new Random(8), IdlePersonality.parseMoods("{}", null));
        p.setLevel("chatty");
        run(p, h, 10 * 60_000, true);
        check("no moods: no sounds", h.count("sound") == 0);
        check("status mentions level", p.status().startsWith("idle: chatty"));

        System.out.println("idle: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
