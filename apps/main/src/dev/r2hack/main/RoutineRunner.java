package dev.r2hack.main;

import org.json.JSONObject;

/**
 * Runs one routine on the calling thread (a Sequencer job), step by step. Between steps and every
 * 100 ms during waits it checks the safety rules and ends the routine with Abort when one trips:
 * the overall timeout, the controller reporting a new error, R2 going on the charger (unless the
 * routine says while_charging), or the battery below the agent's driving limit. Cancelling (stop,
 * a new action, the agent connection dropping) interrupts the thread; the Sequencer then sends stop.
 * A routine that drives doesn't start while the drive lock is closed, and ends when it closes.
 * Its other steps run normally.
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh).
 */
final class RoutineRunner {
    /** What the runner needs from the robot. */
    interface Host {
        /** Sends one controller command. */
        void send(JSONObject cmd);

        void playSound(int number);

        /** Plays a newly generated chirp in this mood (Chirp.MOODS or "random"). */
        void playChirp(String mood);

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

        /** False while the agent's drive lock keeps the wheels locked (docs/drive-lock.md). */
        boolean driveAllowed();
    }

    static final String DRIVE_LOCKED = "driving is locked (enable it first)";

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

    /** Controller modes that drive the robot (turns, forward, patrol, circle; docs/serial-protocol.md). */
    static final int[] MOVING_MODES = {2, 3, 4, 5, 9, 12};
    /** Built-in actions that drive the robot. */
    static final String[] MOVING_ACTIONS = {"turn-left", "turn-right", "turn-around", "forward", "circle", "dance"};

    private final Host host;
    private final Clock clock;
    private boolean stationary;
    private boolean forceWhileCharging;
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

    /**
     * Stationary: steps that drive (drive, moving modes, "move" commands, the turning actions) are skipped,
     * so R2 stays where he is (watch-with-me reactions). Sounds, head, lights and the rest still run.
     */
    RoutineRunner stationary() {
        stationary = true;
        return this;
    }

    /** Runs on the charger too, whatever the routine says (only useful together with stationary()). */
    RoutineRunner whileCharging() {
        forceWhileCharging = true;
        return this;
    }

    /** Whether a step would drive the robot (not counting steps inside repeat blocks or other routines). */
    static boolean moves(Routine.Step s) {
        switch (s.kind) {
            case DRIVE:
                return true;
            case MODE:
                for (int m : MOVING_MODES) if (m == s.number) return true;
                return false;
            case CMD:
                return "move".equals(s.json.optString("cmd"));
            case ACTION:
                return Routine.contains(MOVING_ACTIONS, s.name);
            default:
                return false;
        }
    }

    /** Runs r to the end. Throws Abort (a safety rule) or InterruptedException (cancelled). */
    void run(Routine r) throws Exception {
        top = r;
        deadline = clock.now() + r.timeoutMs;
        errorAtStart = host.error();
        check();
        if (!host.driveAllowed() && drives(r.steps, 0)) throw new Abort(DRIVE_LOCKED);
        runSteps(r.steps, 0, r.name);
    }

    /** Whether a controller command makes the wheels move (the agent's MotionGuard uses the same rule). */
    static boolean drives(JSONObject cmd) {
        String c = cmd.optString("cmd");
        if ("move".equals(c)) return cmd.optInt("power") != 0;
        if ("mode".equals(c)) {
            int m = cmd.optInt("mode", -1);
            return m == 2 || m == 3 || m == 4 || m == 5 || m == 9 || m == 12;
        }
        return false;
    }

    /** Whether any of these steps (or a routine they call) can drive. */
    private boolean drives(java.util.List<Routine.Step> steps, int calls) {
        if (calls > MAX_CALL_DEPTH) return false; // run() reports the loop
        for (Routine.Step s : steps) {
            switch (s.kind) {
                case DRIVE:
                    if (s.power > 0) return true;
                    break;
                case MODE:
                    if (drives(modeCmd(s.number))) return true;
                    break;
                case CMD:
                    if (drives(s.json)) return true;
                    break;
                case ACTION:
                    if (Routine.DRIVE_ACTIONS.contains(s.name)) return true;
                    break;
                case REPEAT:
                    if (drives(s.steps, calls)) return true;
                    break;
                case ROUTINE: {
                    Routine inner = host.load(s.name);
                    if (inner != null && drives(inner.steps, calls + 1)) return true;
                    break;
                }
                default:
                    break;
            }
        }
        return false;
    }

    private static JSONObject modeCmd(int mode) {
        try {
            return new JSONObject().put("cmd", "mode").put("mode", mode);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Sends a command, refusing a drive command while the drive lock is closed. */
    private void send(JSONObject cmd) throws Abort {
        if (drives(cmd) && !host.driveAllowed()) throw new Abort(DRIVE_LOCKED);
        host.send(cmd);
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
        if (stationary && moves(s)) return;
        switch (s.kind) {
            case WAIT:
                pause(s.ms);
                break;
            case SOUND:
            case CHIRP:
                if (s.kind == Routine.Kind.SOUND) host.playSound(s.number);
                else host.playChirp(s.name);
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
                send(new JSONObject().put("cmd", "head-angle").put("angle", s.number));
                break;
            case MODE:
                send(modeCmd(s.number));
                break;
            case DRIVE: {
                long end = clock.now() + s.ms;
                boolean done = false;
                try {
                    while (true) {
                        check();
                        send(new JSONObject().put("cmd", "move").put("power", s.power).put("angle", s.angle));
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
                send(new JSONObject(s.json.toString())); // a copy: the host may change it
                break;
            case ACTION:
                if (Routine.DRIVE_ACTIONS.contains(s.name) && !host.driveAllowed()) throw new Abort(DRIVE_LOCKED);
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
        if (charging > 0 && !top.whileCharging && !forceWhileCharging) throw new Abort("R2 is on the charger");
        int battery = host.field("battery");
        if (battery >= 0 && battery < LOW_BATTERY_PCT && charging <= 0) throw new Abort("battery low (" + battery + "%)");
    }
}
