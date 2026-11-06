package dev.r2hack.main;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Off-robot tests for the chirp generator (Chirp). Run with apps/test.sh, which also passes a folder
 * where a few example chirps are written as WAV files, so they can be listened to without the robot.
 */
public final class ChirpTest {
    private static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        moods();
        variety();
        specials();
        text();
        if (args.length > 0) examples(new File(args[0]));
        System.out.println("chirps: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Every mood, many seeds: audible, not clipped, starts and ends quietly, sensible length and pitch. */
    static void moods() {
        double[][] lengths = {
                // mood index in Chirp.MOODS -> {min s, max s}
                {0.05, 0.6}, {0.4, 3}, {0.4, 3}, {0.3, 2.5}, {0.4, 2.2}, {0.3, 2.5}, {0.4, 3},
        };
        for (int m = 0; m < Chirp.MOODS.length; m++) {
            String mood = Chirp.MOODS[m];
            for (long seed = 0; seed < 200; seed++) {
                short[] pcm = Chirp.render(mood, seed);
                double s = Chirp.seconds(pcm);
                String at = mood + " seed " + seed;
                check(at + ": length " + s + " s outside " + Arrays.toString(lengths[m]), s >= lengths[m][0] && s <= lengths[m][1]);
                check(at + ": peak", peak(pcm) > 8000 && peak(pcm) < Short.MAX_VALUE);
                check(at + ": starts quietly", Math.abs(pcm[0]) < 500);
                check(at + ": ends quietly", Math.abs(pcm[pcm.length - 1]) < 2000);
                for (Chirp.Tone t : Chirp.tones(mood, seed)) {
                    double hi = Math.max(t.f0, t.f1) * (1 + t.wobbleDepth), lo = Math.min(t.f0, t.f1) * (1 - t.wobbleDepth);
                    check(at + ": pitch " + lo + "-" + hi + " Hz", lo >= 300 && hi <= 6500 && hi < Chirp.RATE / 3.0);
                    check(at + ": tone length", t.seconds > 0.02 && t.seconds < 1);
                }
            }
        }
        check("random is a mood", Chirp.knownMood("random") && Chirp.render("random", 1).length > 0);
        check("unknown moods are refused", !Chirp.knownMood("grumpy") && !Chirp.knownMood(null));
        check("unknown mood still plays (chatter)", Chirp.render("grumpy", 1).length > 0);
    }

    /** The point of the feature: the same mood doesn't repeat, unless asked to with the same seed. */
    static void variety() {
        check("same seed, same chirp", Arrays.equals(Chirp.render("ack", 42), Chirp.render("ack", 42)));
        int distinct = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 50; seed++) if (seen.add(Arrays.hashCode(Chirp.render("ack", seed)))) distinct++;
        check("50 acks are all different (" + distinct + ")", distinct == 50);
        // Moods differ in character: sad is lower and slower than excited.
        double sadPitch = 0, excitedPitch = 0, sadTones = 0, excitedTones = 0;
        for (long seed = 0; seed < 50; seed++) {
            for (Chirp.Tone t : Chirp.tones("sad", seed)) { sadPitch += (t.f0 + t.f1) / 2; sadTones++; }
            for (Chirp.Tone t : Chirp.tones("excited", seed)) { excitedPitch += (t.f0 + t.f1) / 2; excitedTones++; }
        }
        check("sad is lower than excited", sadPitch / sadTones < excitedPitch / excitedTones);
        check("sad has fewer tones than excited", sadTones < excitedTones);
        // A question ends on a rise.
        for (long seed = 0; seed < 50; seed++) {
            List<Chirp.Tone> q = Chirp.tones("question", seed);
            Chirp.Tone last = q.get(q.size() - 1);
            check("question " + seed + " ends rising", last.f1 > last.f0 * 1.5);
        }
    }

    /** The set pieces: a wolf whistle and a scream. Known moods, never picked by "random", recognisable shapes. */
    static void specials() {
        for (String mood : Chirp.SPECIALS) {
            check(mood + " is a known mood", Chirp.knownMood(mood));
            for (long seed = 0; seed < 200; seed++) {
                short[] pcm = Chirp.render(mood, seed);
                double s = Chirp.seconds(pcm);
                String at = mood + " seed " + seed;
                check(at + ": length " + s + " s", s >= 0.6 && s <= 3.2);
                check(at + ": peak", peak(pcm) > 8000 && peak(pcm) < Short.MAX_VALUE);
                check(at + ": starts quietly", Math.abs(pcm[0]) < 500);
                check(at + ": ends quietly", Math.abs(pcm[pcm.length - 1]) < 2000);
                for (Chirp.Tone t : Chirp.tones(mood, seed)) {
                    double hi = Math.max(t.f0, t.f1) * (1 + t.wobbleDepth), lo = Math.min(t.f0, t.f1) * (1 - t.wobbleDepth);
                    check(at + ": pitch " + lo + "-" + hi + " Hz", lo >= 300 && hi <= 6500 && hi < Chirp.RATE / 3.0);
                    check(at + ": tone length", t.seconds > 0.02 && t.seconds < 1);
                }
            }
        }
        for (long seed = 0; seed < 50; seed++) {
            // Wolf whistle: two rising tones, the second gliding without a break into a long fall below where it started.
            List<Chirp.Tone> w = Chirp.tones("whistle", seed);
            check("whistle " + seed + " has three tones", w.size() == 3);
            if (w.size() == 3) {
                check("whistle " + seed + " rises twice", w.get(0).f1 > w.get(0).f0 * 2 && w.get(1).f1 > w.get(1).f0 * 2);
                check("whistle " + seed + " has a breath, then no break", w.get(0).gapAfter > 0.05 && w.get(1).legato && w.get(1).gapAfter == 0);
                check("whistle " + seed + " falls from the top", w.get(2).f0 == w.get(1).f1 && w.get(2).f1 < w.get(0).f0);
                check("whistle " + seed + " ends long", w.get(2).seconds > w.get(0).seconds * 2);
            }
            // Scream: the highest, longest tone falls a long way, with a fast wide vibrato.
            Chirp.Tone top = null;
            for (Chirp.Tone t : Chirp.tones("scream", seed)) if (top == null || t.seconds > top.seconds) top = t;
            check("scream " + seed + " shrieks down", top.f0 > 4000 && top.f1 < top.f0 / 2.5 && top.wobbleHz > 20 && top.wobbleDepth >= 0.1);
        }
        // The legato join really is joined: no near-silence in the middle of the whistle's second part.
        short[] pcm = Chirp.render("whistle", 3);
        List<Chirp.Tone> w = Chirp.tones("whistle", 3);
        int join = (int) Math.round((w.get(0).seconds + w.get(0).gapAfter + w.get(1).seconds) * Chirp.RATE);
        int around = 0;
        for (int i = join - 200; i < join + 200; i++) around = Math.max(around, Math.abs(pcm[i]));
        check("whistle is loud across the join (" + around + ")", around > 8000);
        java.util.Set<String> picked = new java.util.HashSet<>();
        for (long seed = 0; seed < 300; seed++) {
            // "random" picks among MOODS; which one isn't exposed, so compare against each special's tones.
            List<Chirp.Tone> r = Chirp.tones("random", seed);
            if (r.size() == 3 && r.get(1).legato) picked.add("whistle");
        }
        check("random never whistles", picked.isEmpty());
        check("specials aren't regular moods", !Arrays.asList(Chirp.MOODS).contains("whistle") && !Arrays.asList(Chirp.MOODS).contains("scream"));
    }

    static void text() {
        double shortS = Chirp.seconds(Chirp.speak("Yes.", null, 1));
        double longS = Chirp.seconds(Chirp.speak("The kitchen lights are on, and the front door is locked. Anything else?", null, 1));
        check("longer text, longer chirp (" + shortS + " < " + longS + ")", shortS < longS);
        check("short text is short (" + shortS + ")", shortS < 1.2);
        StringBuilder essay = new StringBuilder();
        for (int i = 0; i < 400; i++) essay.append("droid ");
        double capped = Chirp.seconds(Chirp.speak(essay.toString(), null, 1));
        check("long text is capped (" + capped + " s)", capped <= Chirp.MAX_SECONDS && capped > Chirp.MAX_SECONDS / 2);
        check("empty text still chirps", Chirp.speak("", null, 1).length > 0);
        check("punctuation only still chirps", Chirp.speak("?!", null, 1).length > 0);
        check("null text still chirps", Chirp.speak(null, "happy", 1).length > 0);
        for (long seed = 0; seed < 30; seed++) {
            List<Chirp.Tone> q = Chirp.textTones("Is the garage door open?", null, seed);
            Chirp.Tone last = q.get(q.size() - 1);
            check("a question's text ends rising (" + seed + ")", last.f1 > last.f0 * 1.5);
            List<Chirp.Tone> st = Chirp.textTones("The garage door is open.", null, seed);
            Chirp.Tone end = st.get(st.size() - 1);
            check("a statement ends falling (" + seed + ")", end.f1 < end.f0);
            check("a sentence has a tone per syllable or so (" + st.size() + ")", st.size() >= 6 && st.size() <= 12);
        }
        check("text with a mood uses its pitch", avgPitch(Chirp.textTones("I am sorry about that.", "sad", 3))
                < avgPitch(Chirp.textTones("I am sorry about that.", "excited", 3)));
        check("non-Latin text still chirps", Chirp.speak("Привет, как дела?", null, 2).length > Chirp.RATE / 4);
    }

    static void examples(File dir) throws Exception {
        dir.mkdirs();
        for (String mood : Chirp.MOODS) {
            for (int i = 1; i <= 3; i++) Files.write(new File(dir, mood + "-" + i + ".wav").toPath(), Chirp.wav(Chirp.render(mood, i)));
        }
        for (String mood : Chirp.SPECIALS) {
            for (int i = 1; i <= 3; i++) Files.write(new File(dir, mood + "-" + i + ".wav").toPath(), Chirp.wav(Chirp.render(mood, i)));
        }
        Files.write(new File(dir, "text-question.wav").toPath(),
                Chirp.wav(Chirp.speak("Do you want me to turn the living room lights off?", null, 7)));
        Files.write(new File(dir, "text-statement.wav").toPath(),
                Chirp.wav(Chirp.speak("The front door is locked, and it is twenty one degrees inside.", null, 7)));
        System.out.println("example chirps written to " + dir);
    }

    private static int peak(short[] pcm) {
        int p = 0;
        for (short s : pcm) p = Math.max(p, Math.abs(s));
        return p;
    }

    private static double avgPitch(List<Chirp.Tone> tones) {
        double sum = 0;
        for (Chirp.Tone t : tones) sum += (t.f0 + t.f1) / 2;
        return sum / tones.size();
    }
}
