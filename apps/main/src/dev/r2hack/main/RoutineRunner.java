package dev.r2hack.main;

import org.json.JSONObject;

/**
 * Runs one routine on the calling thread (a Sequencer job), step by step. Between steps and every
 * 100 ms during waits it checks the safety rules and ends the routine with Abort when one trips:
 * the overall timeout, the controller reporting a new error, R2 going on the charger (unless the
 * routine says while_charging), or the battery below the agent's driving limit. Cancelling (stop,
 * a new action, the agent connection dropping) interrupts the thread; the Sequencer then sends stop.
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class RoutineRunner {
    /** What the runner needs from the robot. */
    interface Host {
        /** Sends one controller command. */
        void send(JSONObject cmd);

        void playSound(int number);

        boolean soundPlaying();

        void stopSound();

        /** Runs a built-in action's steps here, on this thread (not through the Sequencer). */
        void runAction(String name) throws Exception;

        /** A stored routine for a "routine" step, or null. */
        Routine load(String name);

        /** A gin field (Routine.FIELDS); -1 if not known yet. */
        int field(String name);

        /** The controller's error text from gin ("" if none). */
        String error();
    }

    /** A safety rule or a failed wait_until ended the routine. */
    static final class Abort extends Exception {
        Abort(String reason) {
            super(reason);
        }
    }

    static final int LOW_BATTERY_PCT = 10;         // the agent refuses to drive below this
    static final long DRIVE_REPEAT_MS = 500;        // the agent's watchdog stops a move after 3 s without a repeat
    static final long SOUND_WAIT_MAX_MS = 60_000;
    static final int MAX_CALL_DEPTH = 4;            // routine steps calling routines
    private static final long POLL_MS = 100;

    private final Host host;
    private final Clock clock;
    private long deadline;
    private String errorAtStart;
    private Routine top;

    interface Clock {
        long now();

        void sleep(long ms) throws InterruptedException;
    }

    static final Clock REAL_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    };

    RoutineRunner(Host host, Clock clock) {
        this.host = host;
        this.clock = clock;
    }

    /** Runs r to the end. Throws Abort (a safety rule) or InterruptedException (cancelled). */
    void run(Routine r) throws Exception {
        top = r;
        deadline = clock.now() + r.timeoutMs;
        errorAtStart = host.error();
        check();
        runSteps(r.steps, 0, r.name);
    }

    private void runSteps(java.util.List<Routine.Step> steps, int calls, String path) throws Exception {
        for (int i = 0; i < steps.size(); i++) {
            check();
            Routine.Step s = steps.get(i);
            // Drive steps in a row flow into each other without a stop in between (recorded routes).
            boolean driveNext = i + 1 < steps.size() && steps.get(i + 1).kind == Routine.Kind.DRIVE;
            runStep(s, calls, path, driveNext);
        }
    }

    private void runStep(Routine.Step s, int calls, String path, boolean driveNext) throws Exception {
        switch (s.kind) {
            case WAIT:
                pause(s.ms);
                break;
            case SOUND:
                host.playSound(s.number);
                if (s.flag) {
                    long end = clock.now() + SOUND_WAIT_MAX_MS;
                    try {
                        // Give the player a moment to start before asking whether it still plays.
                        pause(POLL_MS);
                        while (host.soundPlaying() && clock.now() < end) pause(POLL_MS);
                    } catch (Exception e) {
                        host.stopSound();
                        throw e;
                    }
                }
                break;
            case HEAD:
                host.send(new JSONObject().put("cmd", "head-angle").put("angle", s.number));
                break;
            case MODE:
                host.send(new JSONObject().put("cmd", "mode").put("mode", s.number));
                break;
            case DRIVE: {
                long end = clock.now() + s.ms;
                boolean done = false;
                try {
                    while (true) {
                        check();
                        host.send(new JSONObject().put("cmd", "move").put("power", s.power).put("angle", s.angle));
                        long left = end - clock.now();
                        if (left <= 0) break;
                        pause(Math.min(DRIVE_REPEAT_MS, left));
                        if (clock.now() >= end) break;
                    }
                    done = true;
                } finally {
                    // Stop unless the next drive step takes over right away; always stop on an abort or cancel.
                    if (!done || !driveNext || s.power == 0) host.send(new JSONObject().put("cmd", "move").put("power", 0).put("angle", 0));
                }
                break;
            }
            case LED:
            case CMD:
                host.send(new JSONObject(s.json.toString())); // a copy: the host may change it
                break;
            case ACTION:
                host.runAction(s.name);
                break;
            case WAIT_UNTIL: {
                long end = clock.now() + s.ms;
                while (!holds(s)) {
                    if (clock.now() >= end) {
                        if (s.flag) return; // else: continue
                        throw new Abort("waited " + s.ms / 1000.0 + " s for " + s.name + " " + s.op + " " + s.value
                                + " (it is " + host.field(s.name) + ")");
                    }
                    pause(POLL_MS);
                }
                break;
            }
            case REPEAT:
                for (int i = 0; i < s.number; i++) runSteps(s.steps, calls, path);
                break;
            case ROUTINE: {
                if (calls + 1 > MAX_CALL_DEPTH) throw new Abort(path + ": routines call each other more than " + MAX_CALL_DEPTH + " deep");
                Routine inner = host.load(s.name);
                if (inner == null) throw new Abort(path + ": no routine named " + s.name);
                runSteps(inner.steps, calls + 1, path + " > " + inner.name);
                break;
            }
            default:
                break;
        }
    }

    private boolean holds(Routine.Step s) {
        int v = host.field(s.name);
        switch (s.op) {
            case "eq": return v == s.value;
            case "ne": return v != s.value;
            case "lt": return v < s.value;
            case "le": return v <= s.value;
            case "gt": return v > s.value;
            case "ge": return v >= s.value;
            default: return false;
        }
    }

    /** Sleeps ms in short slices, checking the safety rules in between. */
    private void pause(long ms) throws Exception {
        long end = clock.now() + ms;
        while (true) {
            check();
            long left = end - clock.now();
            if (left <= 0) return;
            clock.sleep(Math.min(POLL_MS, left));
        }
    }

    private void check() throws Exception {
        if (Thread.interrupted()) throw new InterruptedException();
        if (clock.now() > deadline) throw new Abort("timeout after " + top.timeoutMs / 1000 + " s");
        String err = host.error();
        if (err != null && !err.isEmpty() && !err.equals("0") && !err.equals(errorAtStart)) {
            throw new Abort("controller error: " + err);
        }
        int charging = host.field("charging");
        if (charging > 0 && !top.whileCharging) throw new Abort("R2 is on the charger");
        int battery = host.field("battery");
        if (battery >= 0 && battery < LOW_BATTERY_PCT && charging <= 0) throw new Abort("battery low (" + battery + "%)");
    }
}
