package dev.r2hack.main;

import android.util.Log;
import java.io.File;
import java.util.Arrays;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Robot behavior. Commands are listed in docs/serial-protocol.md.
 * The factory actions are rebuilt here as timed steps of controller commands, head moves and sounds,
 * with the factory timing (each step waits before it runs). Also: modes (ready, sleep, pairing, patrol,
 * user control), status lights, the robot's buttons, and the official phone app (PhoneLink).
 * Run an action with  tools\r2.ps1 action <name>  (names in ACTIONS below).
 * Routines (docs/routines.md) are stored JSON step lists run by RoutineRunner on the same sequencer.
 * A routine named like one of ROUTINE_ACTIONS replaces that action everywhere (voice, buttons, phone app).
 */
final class Brain implements PhoneLink.Host, Voice.Host, FaceTracker.Host, Assist.Host {
    private static final String TAG = "R2Brain";

    static final String[] ACTIONS = {
            "stop", "wake", "turn-left", "turn-right", "turn-around", "forward", "circle", "patrol",
            "dance", "shake-head", "who-are-you", "noise", "not-recognized",
            "lightsaber", "arm", "short-lcd", "long-lcd", "flash-front", "flash-back", "leia", "luke",
            "mute", "unmute", "pair", "phone-app-on", "phone-app-off", "voice-on", "voice-off", "face-on", "face-off",
    };

    /**
     * Built-in actions a routine can run as a step, and that a routine of the same name replaces.
     * The rest of ACTIONS (stop, settings, pairing, patrol) are reserved names.
     */
    static final Set<String> ROUTINE_ACTIONS = Routine.STEP_ACTIONS;

    // The phone app's action numbers (the factory `mode` command) -> action names.
    private static final String[] APP_MODES = {
            "stop", "wake", "turn-around", "turn-left", "turn-right", "forward", "lightsaber", "who-are-you",
            "not-recognized", "patrol", "dance", "not-recognized", "circle", "flash-front", "flash-back",
            "shake-head", "arm", "short-lcd", "long-lcd", "leia", "luke",
    };

    // Sound numbers, as the controller's play_sound uses them.
    private static final int S_WHO = 0, S_SING = 1, S_PAIR_FAIL = 3, S_REPLY = 5, S_NOISE = 6, S_ACK = 7,
            S_WAKE = 8, S_PAIRED = 9, S_HUH = 12, S_FACE = 13, S_LEIA = 100, S_LUKE = 101;
    private static final long STEP_MS = 600;                   // factory timing between steps
    private static final long TURN_DELAY_MS = 700;             // sound first, then the turn
    private static final long PATROL_MS = 60_000;
    private static final long PROJECTOR_FALLBACK_MS = 15_000;  // if its audio can't play
    private static final long CLIP_MAX_MS = 60_000;
    private static final long SLEEP_AFTER_MS = 180_000;        // factory: sleep after 3 minutes idle
    private static final long PAIR_MS = 30_000;
    private static final long DRIVE_SOUND_MS = 2_000;          // droid sounds while driving straight

    private final BrainService io;
    private final Sounds sounds;
    private final Sequencer seq;
    private final DlnaRenderer dlna;
    private final CameraStream camera = new CameraStream();
    private final Robot robot;
    private final Lights lights;
    private final PhoneLink phone;
    private final Voice voice;
    private final Assist assist;
    private final FaceTracker face;
    private final HomeAssistant ha;
    private final RoutineStore routines;
    private volatile String lastRoutine = "";   // how the last routine ended, for listings and /status
    private final CueStore cues;
    private final WatchWithMe watch;

    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long pairUntil;
    private volatile long lastStraightMove;
    private volatile boolean driveSounds;
    private volatile boolean faceActive;

    Brain(BrainService io) {
        this.io = io;
        this.sounds = new Sounds(io);
        this.robot = new Robot(io);
        robot.sounds = sounds;
        this.lights = new Lights(robot, new Lights.Output() {
            @Override
            public void led(int r, int b, int y, int g) {
                try {
                    Brain.this.io.send(new JSONObject().put("cmd", "led").put("r", r).put("b", b).put("y", y).put("g", g));
                } catch (Exception ignored) {
                }
            }
        });
        this.seq = new Sequencer(new Runnable() {
            @Override
            public void run() {
                stopMotion();
            }
        });
        seq.setOnChange(new Runnable() {
            @Override
            public void run() {
                reportRunning();
            }
        });
        this.routines = new RoutineStore(new File(io.getFilesDir(), "routines"), ROUTINE_ACTIONS);
        this.cues = new CueStore(new File(io.getFilesDir(), "cues"), ROUTINE_ACTIONS);
        this.watch = new WatchWithMe(watchHost);
        this.dlna = new DlnaRenderer(io, sounds);
        sounds.onChange = new Runnable() {
            @Override
            public void run() {
                dlna.volumeChanged();
                reportSettings();
            }
        };
        this.phone = new PhoneLink(this, camera);
        phone.setEnabled(io.getSharedPreferences("settings", 0).getBoolean("phone_app", true));
        robot.addListener(phone);
        robot.addListener(new Robot.Listener() {
            @Override
            public void onRobotChanged() {
                reportSettings(); // e.g. the phone app switched voice or faces
            }
        });
        this.voice = new Voice(io, this);
        this.assist = new Assist(io, this);
        voice.setStream(assist);
        this.face = new FaceTracker(this);
        this.ha = new HomeAssistant(haHost());
        watch.setEnabled(io.getSharedPreferences("settings", 0).getBoolean("watch", true));
        robot.addListener(new Robot.Listener() {
            @Override
            public void onRobotChanged() {
                ha.stateChanged();
            }
        });
        camera.setRawListener(face);
        dlna.start();
        camera.start();
        phone.start();
        voice.start();
        ha.start();
        assist.start();
        startTimers();
    }

    String status() {
        String[] modes = {"?", "ready", "sleeping", "pairing", "patrol", "phone control"};
        return "mode: " + modes[robot.mode] + ", action: " + seq.running() + "\n" + sounds.status() + "\n"
                + dlna.status() + "\n" + camera.status() + "\n" + phone.status() + "\n" + voice.status()
                + "\n" + assist.status()
                + "\nface: " + (face.tracking() ? "tracking a face" : faceActive ? "looking" : "off") + "\nname: " + robot.name()
                + "\nbattery: " + (robot.battery < 0 ? "?" : robot.battery + "%")
                + "\nroutines: " + routines.names().size() + (lastRoutine.isEmpty() ? "" : ", last: " + lastRoutine)
                + "\nwatch-with-me: " + watchLine()
                + "\n" + ha.status();
    }

    /** Called each time the bridge connection to the agent is (re)established. */
    void onConnected() {
        Log.i(TAG, "connected");
        lights.reset();
        lights.refresh();
        reportSettings();
        reportRunning();
    }

    /** The bridge to the agent dropped: nothing can reach the controller, so end whatever runs. */
    void onDisconnected() {
        if (!"idle".equals(seq.running())) {
            Log.w(TAG, "agent connection lost, cancelling " + seq.running());
            seq.cancel();
        }
    }

    /** Tells the agent what's running (an action or routine, or idle), for its /status and Home Assistant. */
    private void reportRunning() {
        try {
            io.send(new JSONObject().put("agent", "running").put("name", seq.running()).put("last_routine", lastRoutine));
        } catch (Exception ignored) {
        }
    }

    /** Tells the agent the volume, mute, voice and face settings, for its /status (the web page's controls). */
    private void reportSettings() {
        try {
            io.send(new JSONObject().put("agent", "settings").put("volume", sounds.volume()).put("muted", sounds.muted())
                    .put("voice", robot.voiceRecognition()).put("face", robot.faceDetection())
                    .put("assist", assist.status()));
        } catch (Exception ignored) {
        }
    }

    /** Every message from the controller board: gin status, btn presses, play_sound requests... */
    void onControllerMessage(JSONObject msg) {
        String cmd = msg.optString("cmd");
        if ("gin".equals(cmd)) {
            if (robot.onGin(msg, new Runnable() {
                @Override
                public void run() {
                    lights.refresh(); // charging state settled
                }
            })) {
                lights.refresh();
            }
        } else if ("btn".equals(cmd)) {
            onButton(msg.optInt("value"));
        } else if ("play_sound".equals(cmd)) {
            sounds.play(msg.optInt("sound_id"), msg.optInt("interrupt") == 1);
        }
    }

    /** Messages from the agent itself, e.g. {"agent":"action","name":"dance"} from POST /action. */
    void onAgentMessage(JSONObject msg) {
        String kind = msg.optString("agent");
        switch (kind) {
            case "action":
                action(msg.optString("name"));
                break;
            case "network": {
                boolean ap = msg.optBoolean("ap");
                String ssid = msg.optString("ssid"), ip = msg.optString("ip");
                if (ap != robot.apMode || !ssid.equals(robot.ssid) || !ip.equals(robot.ip)) {
                    robot.apMode = ap;
                    robot.ssid = ssid;
                    robot.ip = ip;
                    robot.changed();
                    lights.refresh();
                }
                break;
            }
            case "volume":
                sounds.setVolume(msg.optInt("percent", sounds.volume()));
                Log.i(TAG, "volume " + sounds.volume() + "%");
                break;
            case "assist": // POST /assist?mode=off|robot|ha[&host=ip]
                Log.i(TAG, assist.configure(msg.optString("mode"), msg.has("host") ? msg.optString("host") : null));
                break;
            case "identity":
                robot.importIdentity(msg.optString("name"), msg.optString("uuid"), msg.optJSONArray("clients"));
                Log.i(TAG, "identity imported: " + robot.name());
                break;
            case "mqtt": // broker settings from POST /mqtt (sent again each time we connect to the agent)
                ha.configure(msg);
                break;
            case "routines":
                if (msg.optString("op").startsWith("cue") || "watch".equals(msg.optString("op"))) onWatchRequest(msg);
                else onRoutinesRequest(msg);
                break;
            case "wifi-list":
            case "wifi-join-result":
                phone.onAgentMessage(msg);
                break;
            default:
                break;
        }
    }

    private void onButton(int value) {
        Log.i(TAG, "button " + value);
        switch (value) {
            case 1: break; // power off: handled by the agent so it works even if this app is hung
            case 2:
                wake();
                io.toAgent("wifi-toggle");
                break;
            case 3: action("pair"); break;
            case 4: action("lightsaber"); break;
            case 5: action("arm"); break;
            case 6: action("patrol"); break;
            default: break;
        }
    }

    /** Runs a named action; unknown names get the "didn't understand" reaction. */
    void action(String name) {
        Log.i(TAG, "action " + name);
        wake();
        switch (name) {
            case "stop":
                watch.stop();
                seq.cancel();
                dlna.stopMedia();
                assist.stop();
                stopMotion();
                sounds.play(S_ACK, true);
                return;
            case "mute":
                sounds.setMuted(true);
                robot.changed();
                return;
            case "unmute":
                sounds.setMuted(false);
                robot.changed();
                sounds.play(S_ACK, true);
                return;
            case "pair":
                if (robot.mode == Robot.PAIR) stopPairing();
                else startPairing();
                return;
            case "voice-on":
            case "voice-off":
                robot.setFlag("voice_recognition", name.endsWith("on")); // the timers apply it
                return;
            case "face-on":
            case "face-off":
                robot.setFlag("face_detection", name.endsWith("on"));
                return;
            case "phone-app-on":
            case "phone-app-off": {
                boolean on = name.endsWith("on");
                io.getSharedPreferences("settings", 0).edit().putBoolean("phone_app", on).apply();
                phone.setEnabled(on);
                return;
            }
            case "patrol":
                if (seq.isRunning("patrol")) { // a second press ends it
                    action("stop");
                    return;
                }
                break;
            default:
                break;
        }
        // A stored routine replaces a built-in action of the same name, and adds new names.
        if ((ROUTINE_ACTIONS.contains(name) || job(name) == null) && routines.exists(name)) {
            runRoutine(name);
            return;
        }
        Sequencer.Job job = job(name);
        if (job == null) {
            seq.start("not-recognized", job("not-recognized"));
            return;
        }
        seq.start(name, job);
    }

    private Sequencer.Job job(String name) {
        switch (name) {
            case "wake":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        lights.refresh();
                        sounds.play(S_WAKE, true);
                        lookAround(40);
                    }
                };
            case "who-are-you":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        sounds.play(S_WHO, true);
                        lookAround(40);
                    }
                };
            case "shake-head":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        sounds.play(S_ACK, true);
                        for (int a : new int[] {-45, 45, -45, 45, 0}) head(a, STEP_MS);
                    }
                };
            case "turn-around": return controllerMove(2);
            case "turn-left": return controllerMove(3);
            case "turn-right": return controllerMove(4);
            case "forward": return controllerMove(5);
            case "circle": return controllerMove(12);
            case "patrol":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        projectorOff();
                        sounds.play(S_ACK, true);
                        send("mode", "mode", 9);
                        robot.setMode(Robot.PATROL);
                        lights.refresh();
                        try {
                            Sequencer.sleep(PATROL_MS);
                            send("mode", "mode", 0);
                        } finally {
                            robot.setMode(Robot.READY);
                            lights.refresh();
                            lastActivity = System.currentTimeMillis();
                        }
                    }
                };
            case "dance":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        // The last turn of each round is left to finish; the controller ends it itself.
                        for (int i = 0; i < 4; i++) {
                            sounds.play(S_SING, true);
                            mode(3, 0); head(-40, STEP_MS); mode(2, STEP_MS); head(40, STEP_MS); mode(3, STEP_MS);
                            head(-40, STEP_MS); head(40, STEP_MS); head(0, STEP_MS); mode(2, STEP_MS);
                        }
                    }
                };
            case "noise": return soundOnly(S_NOISE);
            case "not-recognized": return soundOnly(S_HUH);
            case "lightsaber":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        projectorOff();
                        sounds.play(S_ACK, true);
                        send("lightsaber", "power", robot.lightsaber != 0 ? 0 : 1);
                    }
                };
            case "arm":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        projectorOff();
                        sounds.play(S_ACK, true);
                        send("arm", "power", robot.arm != 0 ? 0 : 1);
                    }
                };
            case "short-lcd": return lcdToggle(true);
            case "long-lcd": return lcdToggle(false);
            case "flash-front": return modeOnly(13); // a toggle: run it again to end the flash
            case "flash-back": return modeOnly(14);
            case "leia": return hologram(1, S_LEIA);
            case "luke": return hologram(2, S_LUKE);
            default: return null;
        }
    }

    // ---- routines ----------------------------------------------------------------------

    /** Starts a stored routine on the sequencer (cancelling what runs). Returns an error, or null. */
    String runRoutine(final String name) {
        final Routine r;
        try {
            r = routines.load(name);
        } catch (Routine.Invalid e) {
            lastRoutine = name + ": not run, " + e.getMessage();
            Log.w(TAG, "routine " + lastRoutine);
            reportRunning();
            return lastRoutine;
        }
        if (r == null) return "no routine named " + name;
        wake();
        Log.i(TAG, "routine " + name);
        final RoutineRunner runner = new RoutineRunner(routineHost, RoutineRunner.REAL_CLOCK);
        seq.start(name, new Sequencer.Job() {
            public void run() throws Exception {
                try {
                    runner.run(r);
                    lastRoutine = name + ": finished";
                } catch (RoutineRunner.Abort a) {
                    lastRoutine = name + ": stopped, " + a.getMessage();
                    Log.w(TAG, "routine " + lastRoutine);
                    stopMotion();
                } catch (InterruptedException cancelled) {
                    lastRoutine = name + ": cancelled";
                    throw cancelled;
                } catch (Exception e) {
                    lastRoutine = name + ": failed, " + e;
                    throw e;
                }
            }
        });
        return null;
    }

    private final RoutineRunner.Host routineHost = new RoutineRunner.Host() {
        @Override
        public void send(JSONObject cmd) {
            io.send(cmd);
        }

        @Override
        public void playSound(int number) {
            sounds.play(number, true);
        }

        @Override
        public boolean soundPlaying() {
            return sounds.isPlaying();
        }

        @Override
        public void stopSound() {
            sounds.stop();
        }

        @Override
        public void runAction(String name) throws Exception {
            Sequencer.Job j = ROUTINE_ACTIONS.contains(name) ? job(name) : null; // always the built-in one
            if (j != null) j.run();
        }

        @Override
        public Routine load(String name) {
            try {
                return routines.load(name);
            } catch (Routine.Invalid e) {
                return null;
            }
        }

        @Override
        public int field(String name) {
            switch (name) {
                case "battery": return robot.battery;
                case "charging": return robot.charging;
                case "arm": return robot.arm;
                case "lightsaber": return robot.lightsaber;
                case "projector": return robot.projector;
                case "lcd_s": return robot.lcdS;
                case "lcd_l": return robot.lcdL;
                default: return -1;
            }
        }

        @Override
        public String error() {
            return robot.error;
        }
    };

    /**
     * The agent's routine API: {"agent":"routines","seq":N,"op":"list|get|put|delete|run|stop",...}.
     * The reply is {"agent":"routines-reply","seq":N,"ok":true|false,...} (error: "error").
     */
    private void onRoutinesRequest(JSONObject msg) {
        JSONObject reply = new JSONObject();
        try {
            reply.put("agent", "routines-reply").put("seq", msg.optInt("seq"));
            String op = msg.optString("op"), name = msg.optString("name");
            switch (op) {
                case "list": {
                    JSONArray list = new JSONArray();
                    for (String n : routines.names()) {
                        JSONObject e = new JSONObject().put("name", n).put("replaces_action", ROUTINE_ACTIONS.contains(n));
                        try {
                            Routine r = routines.load(n);
                            if (r == null) continue;
                            e.put("description", r.description).put("steps", r.stepCount())
                                    .put("timeout", r.timeoutMs / 1000.0).put("while_charging", r.whileCharging);
                        } catch (Routine.Invalid bad) {
                            e.put("error", bad.getMessage());
                        }
                        list.put(e);
                    }
                    reply.put("ok", true).put("routines", list).put("actions", new JSONArray(Arrays.asList(ACTIONS)))
                            .put("running", seq.running()).put("last_routine", lastRoutine);
                    break;
                }
                case "get": {
                    Routine r = routines.load(name);
                    if (r == null) reply.put("ok", false).put("error", "no routine named " + name);
                    else reply.put("ok", true).put("routine", r.source);
                    break;
                }
                case "put": {
                    JSONObject j = msg.optJSONObject("routine");
                    if (j == null) throw new Routine.Invalid("send the routine as a JSON object");
                    if (name.isEmpty()) name = j.optString("name");
                    else if (!j.has("name")) j.put("name", name);
                    if (!name.equals(j.optString("name"))) throw new Routine.Invalid("the name in the URL and in the routine differ");
                    for (String a : ACTIONS) {
                        if (a.equals(name) && !ROUTINE_ACTIONS.contains(name)) {
                            throw new Routine.Invalid("\"" + name + "\" is a reserved action name");
                        }
                    }
                    boolean existed = routines.exists(name);
                    Routine r = routines.save(j);
                    Log.i(TAG, "routine saved: " + r.name);
                    reply.put("ok", true).put("result", (existed ? "replaced " : "saved ") + r.name + " (" + r.stepCount() + " steps)"
                            + (ROUTINE_ACTIONS.contains(r.name) ? "; it now replaces the built-in " + r.name + " action" : ""));
                    break;
                }
                case "delete":
                    if (seq.isRunning(name)) seq.cancel();
                    if (routines.delete(name)) {
                        reply.put("ok", true).put("result", "deleted " + name
                                + (ROUTINE_ACTIONS.contains(name) ? "; the built-in " + name + " action is back" : ""));
                    } else {
                        reply.put("ok", false).put("error", "no routine named " + name);
                    }
                    break;
                case "run": {
                    String err = routines.exists(name) ? runRoutine(name) : "no routine named " + name;
                    if (err == null) reply.put("ok", true).put("result", "running " + name);
                    else reply.put("ok", false).put("error", err);
                    break;
                }
                case "stop": {
                    String was = seq.running();
                    seq.cancel();
                    reply.put("ok", true).put("result", "idle".equals(was) ? "nothing was running" : "stopped " + was);
                    break;
                }
                default:
                    reply.put("ok", false).put("error", "unknown op " + op);
            }
        } catch (Routine.Invalid e) {
            try {
                reply.put("ok", false).put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "routines request failed", e);
            try {
                reply.put("ok", false).put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        io.send(reply);
    }

    /** The factory turns: an acknowledgement, then the controller's own movement. */
    private Sequencer.Job controllerMove(final int mode) {
        return new Sequencer.Job() {
            public void run() throws Exception {
                sounds.play(S_ACK, true);
                mode(mode, TURN_DELAY_MS);
            }
        };
    }

    private Sequencer.Job modeOnly(final int mode) {
        return new Sequencer.Job() {
            public void run() {
                send("mode", "mode", mode);
            }
        };
    }

    private Sequencer.Job soundOnly(final int sound) {
        return new Sequencer.Job() {
            public void run() {
                sounds.play(sound, true);
            }
        };
    }

    /** The factory sends both displays' states together, with the projector off first. */
    private Sequencer.Job lcdToggle(final boolean shortOne) {
        return new Sequencer.Job() {
            public void run() throws Exception {
                boolean s = robot.lcdS >= 2, l = robot.lcdL >= 2;
                if (shortOne) s = !s;
                else l = !l;
                projectorOff();
                sounds.play(S_ACK, true);
                io.send(new JSONObject().put("cmd", "lcd").put("s", s ? 2 : 1).put("l", l ? 2 : 1));
            }
        };
    }

    /**
     * A projector clip with its audio. The projector plays its clip once but keeps reporting "on"
     * until told otherwise, so it is switched off when the audio ends (or after a fallback time).
     * Running it while that projector is on switches it off instead.
     */
    private Sequencer.Job hologram(final int which, final int sound) {
        return new Sequencer.Job() {
            public void run() throws Exception {
                if (robot.projector == which) {
                    projectorOff();
                    return;
                }
                send("projector", "mode", which);
                try {
                    if (!sounds.playAndWait(sound, CLIP_MAX_MS)) Sequencer.sleep(PROJECTOR_FALLBACK_MS);
                } finally {
                    send("projector", "mode", 0);
                }
            }
        };
    }

    private void lookAround(int angle) throws InterruptedException {
        head(-angle, STEP_MS);
        head(angle, STEP_MS);
        head(0, STEP_MS);
    }

    /** Waits, then turns the head: the factory steps carry their delay before the step. */
    private void head(int angle, long waitFirst) throws InterruptedException {
        Sequencer.sleep(waitFirst);
        send("head-angle", "angle", angle);
    }

    private void mode(int m, long waitFirst) throws InterruptedException {
        if (waitFirst > 0) Sequencer.sleep(waitFirst);
        send("mode", "mode", m);
    }

    private void projectorOff() {
        if (robot.projector != 0) send("projector", "mode", 0);
    }

    private void stopMotion() {
        try {
            io.send(new JSONObject().put("cmd", "move").put("power", 0).put("angle", 0));
        } catch (Exception ignored) {
        }
        send("mode", "mode", 0);
    }

    private void send(String cmd, String key, int value) {
        try {
            io.send(new JSONObject().put("cmd", cmd).put(key, value));
        } catch (Exception e) {
            Log.w(TAG, "send failed", e);
        }
    }

    // ---- modes -----------------------------------------------------------------------

    private void startPairing() {
        if (robot.mode == Robot.PATROL) seq.cancel();
        pairUntil = System.currentTimeMillis() + PAIR_MS;
        robot.setMode(Robot.PAIR);
        lights.refresh();
        sounds.play(S_ACK, true);
        Log.i(TAG, "pairing mode for " + PAIR_MS / 1000 + " s: new phones are accepted");
    }

    private void stopPairing() {
        pairUntil = 0;
        if (robot.mode == Robot.PAIR) robot.setMode(Robot.READY);
        lights.refresh();
        lastActivity = System.currentTimeMillis();
    }

    /** Sleep after 3 idle minutes (not while patrolling, pairing or under phone control), pairing timeout, drive sounds. */
    private void startTimers() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int drivePhase = 0;
                long nextDriveSound = 0;
                while (true) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ignored) {
                    }
                    long now = System.currentTimeMillis();
                    if (robot.mode == Robot.READY && now - lastActivity > SLEEP_AFTER_MS) {
                        Log.i(TAG, "sleeping after " + SLEEP_AFTER_MS / 1000 + " s idle");
                        robot.setMode(Robot.SLEEP);
                        lights.refresh();
                    }
                    // Face detection runs while ready (not sleeping, patrolling, pairing or under phone control).
                    boolean wantFace = robot.faceDetection() && robot.mode == Robot.READY;
                    if (wantFace != faceActive) {
                        faceActive = wantFace;
                        face.setEnabled(wantFace);
                        camera.setDetecting(wantFace);
                    }
                    if (face.tracking()) lastActivity = now; // someone is there: stay awake
                    watch.tick(android.os.SystemClock.elapsedRealtime());
                    if (watch.active()) lastActivity = now; // watching a film together: stay awake
                    voice.setEnabled(robot.voiceRecognition());
                    if (robot.mode == Robot.PAIR && now > pairUntil) {
                        Log.i(TAG, "pairing mode ended");
                        stopPairing();
                    }
                    // Factory: while driving straight, a droid sound every other 2 s beat, alternating two sounds.
                    if (driveSounds && now - lastStraightMove > 1000) driveSounds = false;
                    if (driveSounds && now >= nextDriveSound) {
                        if (drivePhase == 0) sounds.play(S_SING, true);
                        else if (drivePhase == 2) sounds.play(S_REPLY, true);
                        drivePhase = (drivePhase + 1) % 4;
                        nextDriveSound = now + DRIVE_SOUND_MS;
                    }
                    if (!driveSounds) {
                        drivePhase = 0;
                        nextDriveSound = now + DRIVE_SOUND_MS;
                    }
                }
            }
        }, "brain-timers");
        t.setDaemon(true);
        t.start();
    }

    // ---- watch-with-me (docs/watch-with-me.md) -------------------------------------------------

    private void setWatchEnabled(boolean on) {
        io.getSharedPreferences("settings", 0).edit().putBoolean("watch", on).apply();
        watch.setEnabled(on);
    }

    private String watchLine() {
        JSONObject w = watch.status(android.os.SystemClock.elapsedRealtime());
        return (w.optBoolean("enabled") ? "" : "switched off, ") + w.optString("state")
                + (w.optString("cue").isEmpty() ? "" : " " + w.optString("cue") + " at " + w.optString("position_text"))
                + (w.optString("last").isEmpty() ? "" : ", last: " + w.optString("last"));
    }

    private final WatchWithMe.Host watchHost = new WatchWithMe.Host() {
        @Override
        public CueFile loadCue(String name) {
            try {
                return cues.load(name);
            } catch (Routine.Invalid e) {
                Log.w(TAG, "cue file " + name + ": " + e.getMessage());
                return null;
            }
        }

        @Override
        public CueFile findCue(String title) {
            return cues.find(title);
        }

        /**
         * Runs a reaction on the sequencer, stationary and allowed on the charger. It doesn't interrupt
         * anything else you started (an action, a routine, patrol, pairing, phone control); an earlier
         * reaction still running is replaced.
         */
        @Override
        public void fire(CueFile file, CueFile.Cue cue) {
            String running = seq.running();
            if (!"idle".equals(running) && !running.startsWith("watch-")) {
                Log.i(TAG, "watch-with-me: skipped " + cue.what() + ", busy with " + running);
                return;
            }
            if (robot.mode == Robot.PAIR || robot.mode == Robot.PATROL || robot.mode == Robot.USER_CONTROL) {
                Log.i(TAG, "watch-with-me: skipped " + cue.what() + " (mode " + robot.mode + ")");
                return;
            }
            final Routine r;
            if (cue.steps != null) {
                r = cue.steps;
            } else {
                Routine stored = routineHost.load(cue.routine);
                if (stored == null) {
                    Log.w(TAG, "watch-with-me: no usable routine named " + cue.routine);
                    return;
                }
                r = stored;
            }
            wake();
            final RoutineRunner runner = new RoutineRunner(routineHost, RoutineRunner.REAL_CLOCK).stationary().whileCharging();
            seq.start("watch-" + file.name, new Sequencer.Job() {
                public void run() throws Exception {
                    try {
                        runner.run(r);
                    } catch (RoutineRunner.Abort a) {
                        Log.w(TAG, "watch-with-me reaction stopped: " + a.getMessage());
                        stopMotion();
                    }
                }
            });
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, msg);
        }

        @Override
        public void changed() {
            ha.stateChanged();
        }
    };

    /**
     * Cue files and the watch session, through the agent's routine channel:
     * op cue-list | cue-get | cue-put | cue-delete | watch (with "watch": the update, or none for the status).
     */
    private void onWatchRequest(JSONObject msg) {
        JSONObject reply = new JSONObject();
        try {
            reply.put("agent", "routines-reply").put("seq", msg.optInt("seq"));
            String op = msg.optString("op"), name = msg.optString("name");
            switch (op) {
                case "cue-list": {
                    JSONArray list = new JSONArray();
                    for (String n : cues.names()) {
                        JSONObject e = new JSONObject().put("name", n);
                        try {
                            CueFile c = cues.load(n);
                            if (c == null) continue;
                            e.put("title", c.title).put("description", c.description).put("cues", c.cues.size())
                                    .put("match", new JSONArray(c.match)).put("offset", c.offset);
                        } catch (Routine.Invalid bad) {
                            e.put("error", bad.getMessage());
                        }
                        list.put(e);
                    }
                    reply.put("ok", true).put("cues", list).put("watch", watch.status(android.os.SystemClock.elapsedRealtime()));
                    break;
                }
                case "cue-get": {
                    CueFile c = cues.load(name);
                    if (c == null) reply.put("ok", false).put("error", "no cue file named " + name);
                    else reply.put("ok", true).put("cue", c.source);
                    break;
                }
                case "cue-put": {
                    JSONObject j = msg.optJSONObject("cue");
                    if (j == null) throw new Routine.Invalid("send the cue file as a JSON object");
                    if (name.isEmpty()) name = j.optString("name");
                    else if (!j.has("name")) j.put("name", name);
                    if (!name.equals(j.optString("name"))) throw new Routine.Invalid("the name in the URL and in the cue file differ");
                    boolean existed = cues.exists(name);
                    CueFile c = cues.save(j);
                    Log.i(TAG, "cue file saved: " + c.name);
                    StringBuilder missing = new StringBuilder();
                    for (CueFile.Cue q : c.cues) {
                        if (q.routine != null && !routines.exists(q.routine) && missing.indexOf(q.routine) < 0) {
                            missing.append(missing.length() > 0 ? ", " : "").append(q.routine);
                        }
                    }
                    reply.put("ok", true).put("result", (existed ? "replaced " : "saved ") + c.name + " (" + c.cues.size() + " cues)"
                            + (missing.length() > 0 ? "; upload these routines too: " + missing : ""));
                    break;
                }
                case "cue-delete":
                    if (name.equals(watch.cueName())) watch.stop();
                    if (cues.delete(name)) reply.put("ok", true).put("result", "deleted " + name);
                    else reply.put("ok", false).put("error", "no cue file named " + name);
                    break;
                case "watch": {
                    long now = android.os.SystemClock.elapsedRealtime();
                    JSONObject w = msg.optJSONObject("watch");
                    if (w != null && w.has("enabled")) {
                        setWatchEnabled(w.optBoolean("enabled"));
                        w.remove("enabled");
                    }
                    if (w != null && w.length() > 0) {
                        try {
                            reply.put("result", watch.update(w, false, now));
                        } catch (IllegalArgumentException e) {
                            reply.put("ok", false).put("error", e.getMessage());
                            break;
                        }
                    }
                    reply.put("ok", true).put("watch", watch.status(now)).put("topic", ha.watchTopic());
                    break;
                }
                default:
                    reply.put("ok", false).put("error", "unknown op " + op);
            }
        } catch (Routine.Invalid e) {
            try {
                reply.put("ok", false).put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "watch request failed", e);
            try {
                reply.put("ok", false).put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        io.send(reply);
    }

    // ---- Home Assistant ---------------------------------------------------------------------

    private HomeAssistant.Host haHost() {
        return new HomeAssistant.Host() {
            @Override
            public JSONObject haState() {
                JSONObject s = new JSONObject();
                try {
                    int m = robot.mode;
                    s.put("battery", robot.battery).put("charging", robot.charging)
                            .put("lightsaber", robot.lightsaber != 0).put("arm", robot.arm != 0)
                            .put("projector", robot.projector).put("lcd_s", robot.lcdS >= 2).put("lcd_l", robot.lcdL >= 2)
                            .put("mode", m >= 1 && m <= HomeAssistant.MODES.length ? HomeAssistant.MODES[m - 1] : "ready")
                            .put("action", seq.running()).put("error", robot.error)
                            .put("muted", sounds.muted()).put("volume", sounds.volume())
                            .put("voice", robot.voiceRecognition()).put("face", robot.faceDetection())
                            .put("name", robot.name()).put("uuid", robot.uuid()).put("version", BuildInfo.VERSION)
                            .put("watch", watch.status(android.os.SystemClock.elapsedRealtime()));
                } catch (Exception ignored) {
                }
                return s;
            }

            @Override
            public void action(String name) {
                Brain.this.action(name);
            }

            @Override
            public java.util.List<String> routineNames() {
                return routines.names();
            }

            @Override
            public void runRoutine(String name) {
                Brain.this.runRoutine(name);
            }

            @Override
            public void projector(int mode) {
                Brain.this.projector(mode);
            }

            @Override
            public void setVolume(int percent) {
                sounds.setVolume(percent);
            }

            @Override
            public void log(String msg) {
                Log.i(TAG, msg);
            }

            @Override
            public void watchUpdate(JSONObject msg) {
                try {
                    Log.i(TAG, "home assistant: watch " + watch.update(msg, true, android.os.SystemClock.elapsedRealtime()));
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "home assistant: watch update ignored: " + e.getMessage());
                }
            }

            @Override
            public void setWatch(boolean on) {
                setWatchEnabled(on);
            }

            @Override
            public void haStatus(String state) {
                try {
                    io.send(new JSONObject().put("agent", "mqtt-status").put("state", state));
                } catch (Exception ignored) {
                }
            }
        };
    }

    // ---- Voice.Host and FaceTracker.Host ----------------------------------------------------

    @Override
    public void onVoiceCommand(String action) {
        action(action);
    }

    @Override
    public void onVoiceAwake(boolean awake) {
        robot.listening = awake;
        lights.refresh();
    }

    /**
     * No listening while patrolling or pairing, or during projector audio or music. Sleeping R2 still listens,
     * so "hey R2-D2" can wake him (commands only count after the wake phrase anyway).
     */
    @Override
    public boolean mayListen() {
        int m = robot.mode;
        return m != Robot.PATROL && m != Robot.PAIR && !seq.isRunning("leia") && !seq.isRunning("luke")
                && !dlna.playing() && !assist.speaking();
    }

    // ---- Assist.Host (Home Assistant's voice pipeline) ----------------------------------------

    @Override
    public void onAssistWake(boolean sound) {
        wake();
        if (sound) sounds.play(S_ACK, true); // a short chirp: R2 heard "hey R2-D2" and is listening
    }

    @Override
    public void onAssistState(int state) {
        robot.assistState = state;
        lastActivity = System.currentTimeMillis(); // a conversation keeps R2 awake
        lights.refresh();
    }

    @Override
    public void onAssistStatus() {
        reportSettings();
    }

    @Override
    public void onAssistError(String text) {
        sounds.play(S_HUH, true);
    }

    @Override
    public boolean playingSound() {
        return sounds.isPlaying();
    }

    @Override
    public String robotName() {
        return robot.name();
    }

    @Override
    public void onFaceTracking(boolean tracking) {
        robot.faceSeen = tracking;
        if (tracking) {
            wake();
            sounds.play(S_FACE, false);
        }
        lights.refresh();
    }

    @Override
    public void shiftHead(int degrees) {
        if (seq.running().equals("idle")) send("head-shift", "angle", degrees); // don't fight a running action
    }

    // ---- PhoneLink.Host ------------------------------------------------------------------

    @Override
    public Robot robot() {
        return robot;
    }

    @Override
    public Sounds sounds() {
        return sounds;
    }

    @Override
    public void appMode(int mode) {
        action(mode >= 0 && mode < APP_MODES.length ? APP_MODES[mode] : "not-recognized");
    }

    @Override
    public void drive(int power, int angle) {
        seq.cancel();
        try {
            io.send(new JSONObject().put("cmd", "move").put("power", power).put("angle", angle));
        } catch (Exception ignored) {
        }
        if (angle == 0 || angle == 180) {
            lastStraightMove = System.currentTimeMillis();
            driveSounds = power > 0;
        }
        if (power > 0 && angle == 0) send("head-angle", "angle", 0); // the head faces forward when driving ahead
    }

    @Override
    public void head(int angle) {
        seq.cancel();
        send("head-angle", "angle", angle);
    }

    @Override
    public void controller(JSONObject cmd) {
        io.send(cmd);
    }

    @Override
    public void projector(int mode) {
        if (mode == 0) {
            if (seq.isRunning("leia") || seq.isRunning("luke")) seq.cancel();
            send("projector", "mode", 0);
        } else if (robot.projector != mode) {
            action(mode == 1 ? "leia" : "luke");
        }
    }

    @Override
    public void powerOff() {
        io.toAgent("shutdown");
    }

    @Override
    public void toAgent(JSONObject msg) {
        io.send(msg);
    }

    @Override
    public void wake() {
        lastActivity = System.currentTimeMillis();
        if (robot.mode == Robot.SLEEP) {
            Log.i(TAG, "waking up");
            robot.setMode(Robot.READY);
            lights.refresh();
        }
    }

    @Override
    public void pairingDone() {
        sounds.play(S_PAIRED, false);
        stopPairing();
    }

    @Override
    public void userControl(boolean anyone) {
        if (anyone && (robot.mode == Robot.READY || robot.mode == Robot.SLEEP)) {
            robot.setMode(Robot.USER_CONTROL);
            lights.refresh();
        } else if (!anyone && robot.mode == Robot.USER_CONTROL) {
            robot.setMode(Robot.READY);
            lastActivity = System.currentTimeMillis();
            lights.refresh();
        }
    }
}
