package dev.r2hack.agent;

/**
 * The drive lock ("arm before driving"). When the lock is on, R2's wheels only move after an explicit
 * enable (web page, tools\r2.ps1 drive-enable, Home Assistant, or POST /drive?enable=1). Driving then
 * stays enabled until it has been idle for the chosen number of minutes, the emergency stop or the
 * low-battery stop fires, or someone disables it. Enabling is never stored, so after a reboot R2 is
 * locked again. With the lock off (the default, as in the factory software) driving always works.
 *
 * Only the wheels are locked (move, and the controller's turn, forward, circle and patrol modes).
 * Head moves stay allowed: face tracking and the actions use them, and MotionGuard caps them anyway.
 *
 * Plain Java, so it can be tested off the robot (apps/test.sh).
 */
final class DriveLock {
    interface Clock {
        long now();
    }

    static final Clock REAL_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    static final int DEFAULT_MINUTES = 10;
    static final int MAX_MINUTES = 120;

    private final Clock clock;
    private volatile boolean required;
    private volatile long enabledUntil;   // 0 = not enabled
    private volatile long idleMs;

    DriveLock(boolean required, Clock clock) {
        this.required = required;
        this.clock = clock;
    }

    /** Whether the lock is on at all. */
    boolean required() {
        return required;
    }

    /** Turning the lock on also drops any earlier enable; turning it off lets everything drive. */
    void setRequired(boolean on) {
        required = on;
        enabledUntil = 0;
    }

    /** Enables driving until it has been idle for this many minutes (1 to MAX_MINUTES). */
    void enable(int minutes) {
        if (minutes < 1 || minutes > MAX_MINUTES) throw new IllegalArgumentException("minutes must be 1 to " + MAX_MINUTES);
        idleMs = minutes * 60_000L;
        enabledUntil = clock.now() + idleMs;
    }

    void disable() {
        enabledUntil = 0;
    }

    /** Enabled and not yet timed out. Meaningful when required() is true. */
    boolean enabled() {
        long until = enabledUntil;
        return until != 0 && clock.now() < until;
    }

    /** May a drive command go to the controller now? */
    boolean allowed() {
        return !required || enabled();
    }

    /** A drive command went out (or R2 is still moving): restart the idle timer. */
    void touch() {
        if (enabled()) enabledUntil = clock.now() + idleMs;
    }

    /** Once the enable has run out, forgets it and returns true (once), so the caller can log and stop. */
    boolean expire() {
        long until = enabledUntil;
        if (until == 0 || clock.now() < until) return false;
        enabledUntil = 0;
        return true;
    }

    long secondsLeft() {
        long until = enabledUntil;
        return until == 0 ? 0 : Math.max(0, (until - clock.now() + 999) / 1000);
    }

    int idleMinutes() {
        return idleMs == 0 ? DEFAULT_MINUTES : (int) (idleMs / 60_000L);
    }

    /** A short summary, also used to notice changes. */
    String state() {
        return !required ? "off" : enabled() ? "enabled" : "locked";
    }
}
