package dev.r2hack.main;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Off-robot tests for the clap and loud-sound detector, with made-up audio: room noise, claps (a short
 * burst of noise that dies away fast), a long loud sound, and speech-like bursts. Run with apps/test.sh.
 */
public final class SoundReactionsTest {
    private static int passed, failed;
    private static final int RATE = SoundReactions.RATE;

    /** Builds audio, then feeds it to a detector in the 0.4 s chunks the voice loop reads. */
    static final class Audio {
        final List<Short> samples = new ArrayList<>();
        final Random rnd = new Random(42);

        /** Room noise at about the given level (dBFS). */
        Audio noise(int ms, double db) {
            double amp = 32768 * Math.pow(10, db / 20);
            for (int i = 0; i < ms * RATE / 1000; i++) add(rnd.nextGaussian() * amp);
            return this;
        }

        /** A clap: a full-strength noise burst that dies away within about 30 ms, over the room noise. */
        Audio clap(double peakDb, double roomDb) {
            double amp = 32768 * Math.pow(10, peakDb / 20), room = 32768 * Math.pow(10, roomDb / 20);
            int n = 120 * RATE / 1000;
            for (int i = 0; i < n; i++) {
                double env = Math.exp(-i / (0.008 * RATE));
                add(rnd.nextGaussian() * (amp * env + room));
            }
            return this;
        }

        /** A steady loud sound (a shout, a vacuum cleaner) with a quick start. */
        Audio loud(int ms, double db) {
            double amp = 32768 * Math.pow(10, db / 20);
            for (int i = 0; i < ms * RATE / 1000; i++) add(amp * 1.4 * Math.sin(2 * Math.PI * 300 * i / RATE));
            return this;
        }

        /** A syllable-like burst that swells and fades (the peak isn't at the start). */
        Audio syllable(int ms, double db, double roomDb) {
            double amp = 32768 * Math.pow(10, db / 20), room = 32768 * Math.pow(10, roomDb / 20);
            int n = ms * RATE / 1000;
            for (int i = 0; i < n; i++) {
                double env = Math.sin(Math.PI * i / n);
                add(amp * env * Math.sin(2 * Math.PI * 180 * i / RATE) + rnd.nextGaussian() * room);
            }
            return this;
        }

        private void add(double v) {
            samples.add((short) Math.max(-32768, Math.min(32767, Math.round(v))));
        }

        List<String> feed() {
            return feed(false);
        }

        List<String> feed(boolean ignore) {
            final List<String> heard = new ArrayList<>();
            SoundReactions d = new SoundReactions(new SoundReactions.Listener() {
                public void onSound(String kind, double peakDb) {
                    heard.add(kind);
                }
            });
            feedInto(d, ignore);
            return heard;
        }

        void feedInto(SoundReactions d, boolean ignore) {
            short[] buf = new short[RATE * 4 / 10];
            int pos = 0;
            while (pos < samples.size()) {
                int n = Math.min(buf.length, samples.size() - pos);
                for (int i = 0; i < n; i++) buf[i] = samples.get(pos + i);
                d.audio(buf, n, ignore);
                pos += n;
            }
        }
    }

    public static void main(String[] args) {
        double room = -55;
        check("quiet room: nothing", new Audio().noise(5000, room).feed(), new String[0]);
        check("one clap", new Audio().noise(2000, room).clap(-6, room).noise(2000, room).feed(), "clap");
        check("two claps 400 ms apart: a double clap",
                new Audio().noise(2000, room).clap(-6, room).noise(280, room).clap(-8, room).noise(2000, room).feed(),
                "double-clap");
        check("two claps 1.5 s apart: two single claps",
                new Audio().noise(2000, room).clap(-6, room).noise(1400, room).clap(-6, room).noise(2000, room).feed(),
                "clap", "clap");
        check("a soft clap is still heard in a quiet room",
                new Audio().noise(2000, room).clap(-28, room).noise(2000, room).feed(), "clap");
        check("a clap barely above a noisy room isn't",
                new Audio().noise(6000, -30).clap(-27, -30).noise(2000, -30).feed(), new String[0]);
        check("a loud clap in a noisy room is",
                new Audio().noise(6000, -30).clap(-3, -30).noise(2000, -30).feed(), "clap");
        check("a long loud sound", new Audio().noise(2000, room).loud(600, -8).noise(2000, room).feed(), "loud");
        check("a long loud sound right after a clap: only the loud one",
                new Audio().noise(2000, room).clap(-6, room).noise(200, room).loud(600, -8).noise(2000, room).feed(),
                "loud");
        check("speech-like syllables are not claps",
                new Audio().noise(2000, room).syllable(250, -20, room).noise(80, room).syllable(300, -22, room)
                        .noise(100, room).syllable(200, -18, room).noise(2000, room).feed(), new String[0]);
        check("a quiet long sound is not loud",
                new Audio().noise(2000, room).loud(800, -30).noise(2000, room).feed(), new String[0]);
        check("while R2 makes noise, nothing is heard",
                new Audio().noise(1000, room).clap(-6, room).noise(500, room).loud(600, -8).noise(1000, room).feed(true),
                new String[0]);
        check("music that keeps playing becomes the new floor, then a clap over it counts",
                new Audio().noise(2000, room).loud(5000, -25).clap(-2, -25).loud(2000, -25).feed(), "clap");

        // The floor follows the room.
        SoundReactions d = new SoundReactions(new SoundReactions.Listener() {
            public void onSound(String kind, double peakDb) {
            }
        });
        new Audio().noise(8000, -40).feedInto(d, false);
        double f = d.floorDb();
        if (Math.abs(f - -40) < 4) pass("noise floor settles near the room level");
        else fail("noise floor settles near the room level", "floor " + f);

        // Noisy room (docs/noisy-room.md): music's drum hits look like claps, many of them.
        SoundReactions n = quietDetector();
        Audio music = new Audio().noise(2000, room);
        for (int i = 0; i < 25; i++) music.clap(-10, room).noise(480, room); // a beat every 0.6 s for 15 s
        music.feedInto(n, false);
        checkTrue("music with a beat: a noisy room", n.noisy());
        new Audio().noise(30_000, room).feedInto(n, false);
        checkTrue("still noisy 30 s after the music stops", n.noisy());
        new Audio().noise(35_000, room).feedInto(n, false);
        checkTrue("quiet again a minute after", !n.noisy());

        SoundReactions few = quietDetector();
        Audio claps = new Audio().noise(2000, room);
        for (int i = 0; i < 5; i++) claps.clap(-6, room).noise(1500, room); // someone clapping a few times
        claps.noise(2000, room).feedInto(few, false);
        checkTrue("a few claps: not a noisy room", !few.noisy());

        SoundReactions spread = quietDetector();
        Audio slow = new Audio().noise(2000, room);
        for (int i = 0; i < 12; i++) slow.clap(-6, room).noise(4000, room); // a clap every 4 s for most of a minute
        slow.feedInto(spread, false);
        checkTrue("claps spread out over a minute: not a noisy room", !spread.noisy());

        SoundReactions loudRoom = quietDetector();
        new Audio().noise(10_000, -32).feedInto(loudRoom, false);
        checkTrue("a loud room (TV turned up): a noisy room", loudRoom.noisy());

        SoundReactions own = quietDetector();
        music.feedInto(own, true);
        checkTrue("R2's own noise doesn't make the room noisy", !own.noisy());

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static SoundReactions quietDetector() {
        return new SoundReactions(new SoundReactions.Listener() {
            public void onSound(String kind, double peakDb) {
            }
        });
    }

    private static void checkTrue(String name, boolean ok) {
        if (ok) pass(name);
        else fail(name, "no");
    }

    private static void check(String name, List<String> got, String... want) {
        List<String> w = java.util.Arrays.asList(want);
        if (got.equals(w)) pass(name);
        else fail(name, "heard " + got + ", expected " + w);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("ok   " + name);
    }

    private static void fail(String name, String why) {
        failed++;
        System.out.println("FAIL " + name + ": " + why);
    }
}
