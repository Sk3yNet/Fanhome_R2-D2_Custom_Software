package dev.r2hack.main;

/**
 * Hears claps and loud sounds in the microphone audio (the same audio the voice commands use).
 * Plain Java with no Android classes, so apps/test.sh can test it off the robot.
 *
 * The audio is cut into 20 ms frames. A slowly adapting noise floor tracks the room (a TV or a fan
 * raises it), and a sound "event" starts when a frame's peak rises well above that floor. When the
 * event ends (the average level is back near the floor) it is classified:
 * <ul>
 * <li><b>clap</b>: short (at most 160 ms) with a sharp start (the loudest part comes first);</li>
 * <li><b>double clap</b>: two claps 150-800 ms apart (a single clap is reported once that window has passed);</li>
 * <li><b>loud</b>: a sound that stays loud for about a quarter second (a shout, a door slam, something dropped).
 * This one is reported as soon as it is recognised, not at the end.</li>
 * </ul>
 * It also notices a <b>noisy room</b>: music or a TV makes many sound events in a short time (each
 * drum hit looks like a clap), or keeps the room itself loud. noisy() is true then, and for a minute
 * after; R2 Main stops reacting to sounds and is stricter with voice commands meanwhile
 * (docs/noisy-room.md).
 * All thresholds are first guesses that need tuning on the robot (see status() and the log).
 */
final class SoundReactions {
    static final String CLAP = "clap", DOUBLE_CLAP = "double-clap", LOUD = "loud";

    interface Listener {
        /** kind is CLAP, DOUBLE_CLAP or LOUD; peakDb is the loudest frame's peak in dBFS (0 = full scale). */
        void onSound(String kind, double peakDb);
    }

    static final int RATE = 16000;
    static final int FRAME = RATE / 50;           // 20 ms
    static final int FRAME_MS = 20;

    // Tunables (dBFS: 0 is the loudest the microphone can report, -90 is near silence).
    static final double START_ABOVE_FLOOR_DB = 20; // an event starts this far above the noise floor...
    static final double START_MIN_DB = -35;        // ...and never below this level
    static final double HOLD_ABOVE_FLOOR_DB = 10;  // the event lasts while frames' average level stays this far above the floor
    static final int END_QUIET_FRAMES = 2;          // 40 ms below that ends the event
    static final int CLAP_MAX_MS = 160;
    static final int CLAP_PEAK_WITHIN_FRAMES = 2;   // the loudest frame is one of the first two
    static final double LOUD_RMS_DB = -15;          // loud sounds: frames this loud (average level)...
    static final int LOUD_MIN_MS = 240;             // ...for this long, in one event
    static final int DOUBLE_MIN_GAP_MS = 150, DOUBLE_MAX_GAP_MS = 800;
    static final int LONG_EVENT_MS = 3000;          // longer than this: the room is louder now
    static final double FLOOR_START_DB = -60, FLOOR_MIN_DB = -90, FLOOR_MAX_DB = -25;
    // Noisy room (music, a TV): this many sound events...
    static final int NOISY_EVENTS = 10;
    static final int NOISY_WINDOW_MS = 20_000;      // ...within this long; people rarely clap or bang that often
    static final double NOISY_FLOOR_DB = -40;       // or the room's own level (the floor) is at least this loud
    static final int NOISY_HOLD_MS = 60_000;        // and it stays "noisy" this long after the last sign of it

    private final Listener listener;

    private volatile long clockMs;     // audio time, from the number of samples seen
    private double floorDb = FLOOR_START_DB;
    private boolean floorSet;
    private final short[] frame = new short[FRAME];
    private int framePos;

    // The event being heard.
    private boolean inEvent;
    private int eventFrames, quietFrames, loudFrames, peakFrame;
    private double eventPeakDb;
    private boolean loudReported;

    // A clap waiting to see whether a second one follows.
    private long pendingClapAt = -1;
    private double pendingClapDb;

    // Noisy room: when the last NOISY_EVENTS events started (a ring), and until when the room counts as noisy.
    private final long[] onsets = new long[NOISY_EVENTS];
    private int onsetPos;
    private volatile long noisyUntil = -1;

    // For status().
    private volatile String last = "nothing yet";
    private volatile long lastAtMs = -1;

    SoundReactions(Listener listener) {
        this.listener = listener;
        java.util.Arrays.fill(onsets, Long.MIN_VALUE / 2);
    }

    /**
     * Adds microphone audio (16 kHz mono). With ignore true (R2 is making his own noise: a sound, a
     * move), the audio only keeps time: any event in progress or pending clap is dropped.
     */
    void audio(short[] buf, int n, boolean ignore) {
        for (int i = 0; i < n; i++) {
            frame[framePos++] = buf[i];
            if (framePos == FRAME) {
                framePos = 0;
                clockMs += FRAME_MS;
                if (ignore) reset();
                else frame(frame);
            }
        }
    }

    double floorDb() {
        return floorDb;
    }

    /** True while the room is noisy (music, a TV), and for NOISY_HOLD_MS after. */
    boolean noisy() {
        return clockMs < noisyUntil;
    }

    String status() {
        return String.format(java.util.Locale.US, "noise floor %.0f dB%s, last heard: %s", floorDb,
                noisy() ? " (noisy room)" : "",
                lastAtMs < 0 ? last : last + " " + (clockMs - lastAtMs) / 1000 + " s ago");
    }

    private void markNoisy() {
        noisyUntil = clockMs + NOISY_HOLD_MS;
    }

    private void reset() {
        inEvent = false;
        pendingClapAt = -1;
    }

    private void frame(short[] f) {
        long sumSq = 0;
        int peak = 0;
        for (short s : f) {
            sumSq += (long) s * s;
            int a = Math.abs((int) s);
            if (a > peak) peak = a;
        }
        double rmsDb = db(Math.sqrt(sumSq / (double) f.length));
        double peakDb = db(peak);
        if (!floorSet) { // start from the room as first heard, not from a guess
            floorSet = true;
            floorDb = Math.max(FLOOR_MIN_DB, Math.min(FLOOR_MAX_DB, rmsDb));
            return;
        }

        // A single clap is reported once no second clap can follow.
        if (pendingClapAt >= 0 && !inEvent && clockMs - pendingClapAt > DOUBLE_MAX_GAP_MS) {
            pendingClapAt = -1;
            report(CLAP, pendingClapDb);
        }

        if (floorDb >= NOISY_FLOOR_DB) markNoisy();

        if (!inEvent) {
            if (peakDb >= Math.max(floorDb + START_ABOVE_FLOOR_DB, START_MIN_DB)) {
                // Many events close together: music or a TV, not people.
                onsets[onsetPos] = clockMs;
                onsetPos = (onsetPos + 1) % NOISY_EVENTS;
                if (clockMs - onsets[onsetPos] <= NOISY_WINDOW_MS) markNoisy(); // the oldest of the last NOISY_EVENTS
                inEvent = true;
                eventFrames = 0;
                quietFrames = 0;
                loudFrames = 0;
                peakFrame = 0;
                eventPeakDb = -999;
                loudReported = false;
            } else {
                // The floor falls quickly to a quieter room and rises slowly (about 4 s) to a louder one.
                floorDb += (rmsDb - floorDb) * (rmsDb < floorDb ? 0.1 : 0.005);
                floorDb = Math.max(FLOOR_MIN_DB, Math.min(FLOOR_MAX_DB, floorDb));
                return;
            }
        }

        eventFrames++;
        if (peakDb > eventPeakDb) {
            eventPeakDb = peakDb;
            peakFrame = eventFrames - 1;
        }
        if (rmsDb >= LOUD_RMS_DB && rmsDb >= floorDb + START_ABOVE_FLOOR_DB) loudFrames++;
        if (!loudReported && loudFrames * FRAME_MS >= LOUD_MIN_MS) {
            loudReported = true;
            pendingClapAt = -1; // a clap just before a loud noise is part of it
            report(LOUD, eventPeakDb);
        }
        if (rmsDb < floorDb + HOLD_ABOVE_FLOOR_DB) quietFrames++;
        else quietFrames = 0;
        if (quietFrames >= END_QUIET_FRAMES) {
            endEvent();
        } else if (eventFrames * FRAME_MS >= LONG_EVENT_MS) {
            // Not a passing noise but the room got louder (music, a vacuum cleaner): that's the new floor.
            inEvent = false;
            pendingClapAt = -1;
            floorDb = Math.max(FLOOR_MIN_DB, Math.min(FLOOR_MAX_DB, rmsDb));
        }
    }

    private void endEvent() {
        inEvent = false;
        if (loudReported) return;
        int ms = (eventFrames - quietFrames) * FRAME_MS;
        boolean clap = ms <= CLAP_MAX_MS && peakFrame < CLAP_PEAK_WITHIN_FRAMES;
        if (!clap) {
            pendingClapAt = -1; // speech or a longer noise breaks a double clap
            return;
        }
        long startMs = clockMs - (long) eventFrames * FRAME_MS;
        if (pendingClapAt >= 0) {
            long gap = startMs - pendingClapAt;
            if (gap >= DOUBLE_MIN_GAP_MS && gap <= DOUBLE_MAX_GAP_MS) {
                pendingClapAt = -1;
                report(DOUBLE_CLAP, Math.max(pendingClapDb, eventPeakDb));
                return;
            }
        }
        pendingClapAt = startMs;
        pendingClapDb = eventPeakDb;
    }

    private void report(String kind, double peakDb) {
        last = String.format(java.util.Locale.US, "%s (%.0f dB)", kind, peakDb);
        lastAtMs = clockMs;
        listener.onSound(kind, peakDb);
    }

    private static double db(double amplitude) {
        return amplitude < 1 ? -96 : 20 * Math.log10(amplitude / 32768.0);
    }
}
