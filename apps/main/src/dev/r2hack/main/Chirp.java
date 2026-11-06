package dev.r2hack.main;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Makes new R2-style droid chirps on the fly, so R2 doesn't repeat the same few recorded beeps.
 * A chirp is a phrase of short tones: blips, pitch glides, warbles (fast vibrato) and trills (fast
 * switching between two pitches), with short gaps between them. A mood picks the recipe; a seed picks
 * the details, so the same mood sounds a little different every time unless you pass the same seed.
 *
 * speak() turns a piece of text into a phrase of about the right length and shape: one or more tones
 * per word, short pauses between words and longer ones between sentences, and an ending that follows
 * the punctuation (rising for a question, a trill for "!"). It's for the beeps-only reply, where R2
 * answers in droid sounds and the words are shown or spoken elsewhere.
 *
 * Plain Java only (no Android), so it can be tested and listened to off the robot (apps/test.sh writes
 * WAV files). Output: 16-bit mono PCM at RATE.
 */
final class Chirp {
    static final int RATE = 22050;
    /** The moods render() knows. "random" picks one of the others. */
    static final String[] MOODS = {"ack", "happy", "excited", "question", "sad", "worried", "chatter"};
    /** Set pieces render() also knows, which "random" never picks: a wolf whistle, and R2's scream. */
    static final String[] SPECIALS = {"whistle", "scream"};
    static final double MAX_SECONDS = 8;          // speak() never makes more than this
    static final int MAX_TEXT = 2000;              // characters of text speak() looks at
    private static final double PEAK = 0.8 * Short.MAX_VALUE;

    /** One tone. Pitch goes f0 -> f1 (exponentially); wobble adds vibrato or, with trill, a second pitch. */
    static final class Tone {
        double seconds, f0, f1;
        double wobbleHz, wobbleDepth;   // vibrato: rate, and depth as a fraction of the pitch
        boolean trill;                  // square wobble: jump between two pitches instead of sliding
        double gapAfter;                // silence after it, in seconds
        double level = 1;
        boolean legato;                 // slides straight into the next tone: no gap, no fade between them

        Tone(double seconds, double f0, double f1) {
            this.seconds = seconds;
            this.f0 = f0;
            this.f1 = f1;
        }
    }

    private final Random rnd;
    private final List<Tone> tones = new ArrayList<>();
    /** Pitch range for this phrase, Hz. Moods move it. */
    private double low = 1100, high = 3600;

    private Chirp(long seed) {
        rnd = new Random(seed);
    }

    static boolean knownMood(String mood) {
        if ("random".equals(mood)) return true;
        for (String m : MOODS) if (m.equals(mood)) return true;
        for (String m : SPECIALS) if (m.equals(mood)) return true;
        return false;
    }

    /** A new phrase in this mood (unknown moods: chatter). */
    static short[] render(String mood, long seed) {
        Chirp c = new Chirp(seed);
        c.compose(mood);
        return c.synth();
    }

    /** The tones render() would play, for tests. */
    static List<Tone> tones(String mood, long seed) {
        Chirp c = new Chirp(seed);
        c.compose(mood);
        return c.tones;
    }

    /** A phrase shaped like text: its length, pauses and ending. mood may be null (from the punctuation). */
    static short[] speak(String text, String mood, long seed) {
        Chirp c = new Chirp(seed);
        c.composeText(text, mood);
        return c.synth();
    }

    static List<Tone> textTones(String text, String mood, long seed) {
        Chirp c = new Chirp(seed);
        c.composeText(text, mood);
        return c.tones;
    }

    // ---- recipes --------------------------------------------------------------------------------

    private void compose(String mood) {
        if ("random".equals(mood)) mood = MOODS[1 + rnd.nextInt(MOODS.length - 1)]; // not "ack"
        switch (mood == null ? "" : mood) {
            case "ack": // the confirmation beep: two or three quick blips, often ending on a short rise
                range(1500, 3400);
                for (int i = 1 + rnd.nextInt(2); i > 0; i--) blip(0.035, 0.07).gapAfter = between(0.015, 0.04);
                if (rnd.nextInt(3) > 0) up(0.06, 0.12);
                else blip(0.05, 0.09);
                break;
            case "happy": // mostly rising, bright, with a trill or two
                range(1500, 3900);
                for (int i = 4 + rnd.nextInt(4); i > 0; i--) {
                    int r = rnd.nextInt(10);
                    if (r < 4) up(0.07, 0.18);
                    else if (r < 6) trill(0.1, 0.2);
                    else if (r < 8) blip(0.04, 0.08);
                    else warble(0.1, 0.2);
                }
                up(0.12, 0.22);
                break;
            case "excited": // fast, high, lots of blips and trills with hardly a gap
                range(2000, 4300);
                for (int i = 7 + rnd.nextInt(5); i > 0; i--) {
                    Tone t = rnd.nextInt(3) == 0 ? trill(0.08, 0.16) : rnd.nextBoolean() ? blip(0.03, 0.06) : up(0.05, 0.1);
                    t.gapAfter = between(0.005, 0.025);
                }
                trill(0.15, 0.25).wobbleHz = between(35, 45);
                break;
            case "question": // a little chatter, then a long rise
                range(1200, 3300);
                for (int i = 1 + rnd.nextInt(3); i > 0; i--) talk();
                Tone q = new Tone(between(0.25, 0.4), between(low, low * 1.3), between(high * 0.85, high));
                q.wobbleHz = between(6, 10);
                q.wobbleDepth = 0.02;
                add(q);
                break;
            case "sad": // one or two long, low, falling whistles with a slow wobble
                range(500, 2000);
                for (int i = 1 + rnd.nextInt(2); i > 0; i--) {
                    Tone t = new Tone(between(0.45, 0.8), between(high * 0.8, high), between(low, low * 1.3));
                    t.wobbleHz = between(4, 7);
                    t.wobbleDepth = between(0.02, 0.05);
                    t.gapAfter = between(0.1, 0.2);
                    add(t);
                }
                break;
            case "worried": // warbles that keep falling, a nervous blip in between
                range(900, 3000);
                double top = high;
                for (int i = 3 + rnd.nextInt(3); i > 0; i--) {
                    Tone t = warble(0.12, 0.25);
                    t.f0 = top;
                    t.f1 = top * between(0.6, 0.8);
                    top = Math.max(low, t.f1 * between(0.95, 1.1));
                    if (rnd.nextInt(3) == 0) blip(0.03, 0.05);
                }
                break;
            case "whistle": { // a wolf whistle: a quick rise, a breath, then up again and a long fall
                double k = between(0.92, 1.08); // a slightly different whistler each time
                Tone first = new Tone(between(0.16, 0.22), 1150 * k, 2900 * k);
                first.wobbleHz = 5;
                first.wobbleDepth = 0.006;
                first.gapAfter = between(0.09, 0.14);
                add(first);
                Tone up = new Tone(between(0.12, 0.16), 1300 * k, 3100 * k);
                up.legato = true;
                add(up);
                Tone fall = new Tone(between(0.45, 0.6), up.f1, 900 * k);
                fall.wobbleHz = 5;
                fall.wobbleDepth = 0.008;
                add(fall);
                break;
            }
            case "scream": { // R2's freak-out: frantic trills, a long shrieking fall, panicky blips, another shriek
                range(2000, 4000);
                for (int i = 2 + rnd.nextInt(2); i > 0; i--) trill(0.06, 0.12).gapAfter = between(0.005, 0.02);
                for (int n = 0; n < 2; n++) {
                    Tone shriek = new Tone(n == 0 ? between(0.7, 0.95) : between(0.4, 0.6),
                            between(4300, 5000), between(1100, 1500));
                    shriek.wobbleHz = between(24, 34);
                    shriek.wobbleDepth = between(0.12, 0.2);
                    shriek.gapAfter = between(0.04, 0.08);
                    add(shriek);
                    if (n == 0) {
                        for (int i = 3 + rnd.nextInt(3); i > 0; i--) {
                            (rnd.nextBoolean() ? blip(0.03, 0.06) : down(0.05, 0.1)).gapAfter = between(0.01, 0.03);
                        }
                    }
                }
                break;
            }
            default: // chatter: R2 "talking", a mix of everything
                range(1100, 3700);
                for (int i = 5 + rnd.nextInt(5); i > 0; i--) talk();
                break;
        }
        if (!tones.isEmpty()) tones.get(tones.size() - 1).gapAfter = 0;
    }

    private void composeText(String text, String mood) {
        if (text == null) text = "";
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        String trimmed = text.trim();
        char end = trimmed.isEmpty() ? '.' : trimmed.charAt(trimmed.length() - 1);
        if (mood == null || mood.isEmpty() || !knownMood(mood)) {
            mood = end == '?' ? "question" : end == '!' ? "excited" : "chatter";
        }
        switch (mood) {
            case "sad": range(600, 2200); break;
            case "worried": range(900, 3000); break;
            case "happy": range(1500, 3900); break;
            case "excited": range(1800, 4200); break;
            default: range(1100, 3600); break;
        }
        // Words -> syllables (vowel groups), 1-3 tones per word. Long texts are thinned out to fit.
        List<int[]> words = new ArrayList<>(); // {syllables, pause after: 0 none, 1 word, 2 comma, 3 sentence}
        for (String w : trimmed.split("\\s+")) {
            if (w.isEmpty()) continue;
            int syl = 0;
            boolean inVowel = false;
            boolean letters = false;
            for (int i = 0; i < w.length(); i++) {
                char ch = Character.toLowerCase(w.charAt(i));
                if (Character.isLetterOrDigit(ch)) letters = true;
                boolean v = "aeiouy0123456789".indexOf(ch) >= 0 || (Character.isLetter(ch) && ch > 127);
                if (v && !inVowel) syl++;
                inVowel = v;
            }
            if (!letters) continue;
            char last = w.charAt(w.length() - 1);
            int pause = ".!?".indexOf(last) >= 0 ? 3 : ",;:".indexOf(last) >= 0 ? 2 : 1;
            words.add(new int[] {Math.max(1, Math.min(3, syl)), pause});
        }
        if (words.isEmpty()) {
            compose(mood);
            return;
        }
        // Budget: about 0.11 s per tone plus pauses; keep under MAX_SECONDS by skipping words evenly.
        double estimate = 0;
        for (int[] w : words) estimate += w[0] * 0.11 + (w[1] == 3 ? 0.22 : w[1] == 2 ? 0.14 : 0.06);
        double keep = Math.min(1, (MAX_SECONDS - 0.5) / estimate);
        double carry = 0;
        for (int wi = 0; wi < words.size(); wi++) {
            int[] w = words.get(wi);
            carry += keep;
            if (carry < 1 && wi < words.size() - 1) continue;
            carry -= 1;
            for (int s = 0; s < w[0]; s++) {
                Tone t = talk();
                t.gapAfter = between(0.01, 0.03);
            }
            Tone lastTone = tones.get(tones.size() - 1);
            lastTone.gapAfter = w[1] == 3 ? between(0.18, 0.26) : w[1] == 2 ? between(0.1, 0.16) : between(0.04, 0.08);
        }
        switch (end) {
            case '?': {
                Tone q = new Tone(between(0.22, 0.35), between(low, low * 1.3), between(high * 0.85, high));
                q.wobbleHz = 8;
                q.wobbleDepth = 0.02;
                add(q);
                break;
            }
            case '!':
                trill(0.14, 0.22);
                break;
            default: {
                Tone t = tones.get(tones.size() - 1); // fall at the end of a statement
                t.f1 = Math.max(low, Math.min(t.f0, t.f1) * 0.75);
                break;
            }
        }
        tones.get(tones.size() - 1).gapAfter = 0;
        // Thinning keeps the estimate in budget; this is the hard cap.
        double total = 0;
        for (int i = 0; i < tones.size(); i++) {
            Tone t = tones.get(i);
            total += t.seconds + t.gapAfter;
            if (total > MAX_SECONDS) {
                tones.subList(i, tones.size()).clear();
                break;
            }
        }
    }

    // ---- building blocks ------------------------------------------------------------------------

    private void range(double lo, double hi) {
        low = lo;
        high = hi;
    }

    private double between(double a, double b) {
        return a + rnd.nextDouble() * (b - a);
    }

    private double pitch() {
        return low * Math.pow(high / low, rnd.nextDouble()); // even on a log scale
    }

    private Tone add(Tone t) {
        if (t.gapAfter == 0 && !t.legato) t.gapAfter = between(0.02, 0.06);
        tones.add(t);
        return t;
    }

    private Tone blip(double minS, double maxS) {
        double f = pitch();
        return add(new Tone(between(minS, maxS), f, f * between(0.97, 1.03)));
    }

    private Tone up(double minS, double maxS) {
        double a = pitch(), b = pitch();
        return add(new Tone(between(minS, maxS), Math.min(a, b), Math.max(a, b) * 1.15));
    }

    private Tone down(double minS, double maxS) {
        double a = pitch(), b = pitch();
        return add(new Tone(between(minS, maxS), Math.max(a, b) * 1.15, Math.min(a, b)));
    }

    private Tone warble(double minS, double maxS) {
        double a = pitch();
        Tone t = add(new Tone(between(minS, maxS), a, a * between(0.85, 1.2)));
        t.wobbleHz = between(14, 28);
        t.wobbleDepth = between(0.08, 0.2);
        return t;
    }

    private Tone trill(double minS, double maxS) {
        double a = pitch();
        Tone t = add(new Tone(between(minS, maxS), a, a * between(0.9, 1.15)));
        t.trill = true;
        t.wobbleHz = between(22, 38);
        t.wobbleDepth = between(0.15, 0.35);
        return t;
    }

    /** One "syllable" of droid talk. */
    private Tone talk() {
        int r = rnd.nextInt(12);
        if (r < 3) return blip(0.04, 0.09);
        if (r < 6) return up(0.06, 0.16);
        if (r < 8) return down(0.06, 0.16);
        if (r < 10) return warble(0.08, 0.18);
        return trill(0.08, 0.16);
    }

    // ---- synthesis ------------------------------------------------------------------------------

    private short[] synth() {
        int total = 0;
        for (Tone t : tones) total += samples(t.seconds) + samples(t.gapAfter);
        short[] out = new short[total];
        int pos = 0;
        double phase = 0;
        boolean joined = false; // the previous tone slides into this one
        for (Tone t : tones) {
            int n = samples(t.seconds);
            int attack = joined ? 0 : Math.min(n / 4, samples(0.004));
            int release = t.legato ? 0 : Math.min(n / 3, samples(0.012));
            double wobPhase = rnd.nextDouble();
            double glide = Math.pow(t.f1 / t.f0, 1.0 / Math.max(1, n)), base = t.f0; // exponential glide, per sample
            for (int i = 0; i < n; i++) {
                double f = base;
                base *= glide;
                if (t.wobbleHz > 0) {
                    double w = Math.sin(2 * Math.PI * (wobPhase + t.wobbleHz * i / RATE));
                    if (t.trill) w = w >= 0 ? 1 : -1;
                    f *= 1 + t.wobbleDepth * w;
                }
                phase += f / RATE;
                phase -= Math.floor(phase);
                double p = 2 * Math.PI * phase;
                // A sine with a little third and fifth harmonic: brighter and more "electronic" than a pure tone.
                double v = Math.sin(p) + 0.22 * Math.sin(3 * p) + 0.08 * Math.sin(5 * p);
                double env = 1;
                if (i < attack) env = (double) i / attack;
                else if (i >= n - release) env = (double) (n - i) / release;
                out[pos + i] = (short) Math.round(v / 1.3 * env * t.level * PEAK);
            }
            joined = t.legato && t.gapAfter == 0;
            if (!joined) phase = 0; // each tone starts from silence anyway (envelope), so no click
            pos += n + samples(t.gapAfter);
        }
        return out;
    }

    private static int samples(double seconds) {
        return (int) Math.round(seconds * RATE);
    }

    static double seconds(short[] pcm) {
        return (double) pcm.length / RATE;
    }

    /** A WAV file of pcm (for listening off the robot). */
    static byte[] wav(short[] pcm) {
        int data = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + data)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(data);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }
}
