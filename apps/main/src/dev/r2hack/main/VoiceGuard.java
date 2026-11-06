package dev.r2hack.main;

/**
 * Keeps background noise (a TV, music) from driving R2 around with made-up voice commands
 * (docs/noisy-room.md). While R2 is being careful (a noisy room, or TV mode), a voice command that
 * moves him only runs when it is heard twice within CONFIRM_MS: the first time he only asks "hm?".
 * A sound that happens to match "patrol" once is common in music; the same command twice in a few
 * seconds is not. Commands that don't move him, and every command while he isn't being careful, run
 * at once. Plain Java with no Android classes, so apps/test.sh can test it off the robot.
 */
final class VoiceGuard {
    static final long CONFIRM_MS = 8_000;

    /** What to do with a voice command. */
    enum Verdict { RUN, ASK }

    private String pending;
    private long pendingUntil;

    /**
     * moves: the command drives the wheels. careful: the room is noisy or TV mode is on. now: any clock in ms.
     * A command that doesn't need confirming also forgets one waiting to be confirmed ("stop" cancels it).
     */
    synchronized Verdict check(String action, boolean moves, boolean careful, long now) {
        if (!moves || !careful) {
            pending = null;
            return Verdict.RUN;
        }
        if (action.equals(pending) && now <= pendingUntil) {
            pending = null;
            return Verdict.RUN;
        }
        pending = action;
        pendingUntil = now + CONFIRM_MS;
        return Verdict.ASK;
    }

    /** The command waiting to be said again, or null. */
    synchronized String pending(long now) {
        return pending != null && now <= pendingUntil ? pending : null;
    }
}
