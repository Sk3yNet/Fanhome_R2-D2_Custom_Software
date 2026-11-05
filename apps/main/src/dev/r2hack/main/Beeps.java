package dev.r2hack.main;

import java.util.Random;

/**
 * Which factory droid sounds R2 makes in place of a spoken reply (Assist's beep replies): more sounds for a longer
 * answer, picked at random from a few chatty ones so the same answer doesn't always sound the same.
 * Used when generated chirps are off (chirps-off); otherwise Brain's beeper plays Chirp.speak().
 *
 * No Android classes, so it can be tested on a plain JVM.
 */
final class Beeps {
    /** About one sound per this many words, between MIN_SOUNDS and MAX_SOUNDS. */
    static final int WORDS_PER_SOUND = 6;
    static final int MIN_SOUNDS = 1, MAX_SOUNDS = 4;

    private Beeps() {
    }

    static int words(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** How many sounds a reply gets. */
    static int count(String text) {
        int n = 1 + words(text) / WORDS_PER_SOUND;
        return Math.max(MIN_SOUNDS, Math.min(MAX_SOUNDS, n));
    }

    /** The sounds for a reply, from pool, never the same one twice in a row (if the pool has two or more). */
    static int[] plan(String text, int[] pool, Random rnd) {
        int[] out = new int[count(text)];
        int last = -1;
        for (int i = 0; i < out.length; i++) {
            int pick;
            do {
                pick = pool[rnd.nextInt(pool.length)];
            } while (pool.length > 1 && pick == last);
            out[i] = pick;
            last = pick;
        }
        return out;
    }
}
