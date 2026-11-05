package dev.r2hack.main;

import android.util.Log;
import java.io.File;
import java.util.Arrays;
import java.util.List;
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
 * Places (docs/places.md): routes taught with the joystick, replayed as trips; "goto:<place>" runs one.
 * Liveliness (docs/liveliness.md) is one setting, off / quiet / chatty / full, for how much R2 does on his own:
 * read it with liveliness(). IdlePersonality uses it for idle chatter; other features read the same getter.
 */
final class Brain implements PhoneLink.Host, Voice.Host, FaceTracker.Host, Assist.Host {
    private static final String TAG = "R2Brain";

    static final String[] ACTIONS = {
            "stop", "wake", "turn-left", "turn-right", "turn-around", "forward", "circle", "patrol",
            "dance", "shake-head", "who-are-you", "noise", "not-recognized", "chirp",
            "lightsaber", "arm", "short-lcd", "long-lcd", "flash-front", "flash-back", "leia", "luke",
            "mute", "unmute", "pair", "phone-app-on", "phone-app-off", "voice-on", "voice-off", "face-on", "face-off",
            "go-home", "tour",
            "liveliness-off", "liveliness-quiet", "liveliness-chatty", "liveliness-full",
            "chirps-on", "chirps-off",
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
    private static final long SLEEP_AFTER_FULL_MS = 600_000;   // activity "full": the show runs 10 minutes
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
    private final HomeAssistantPlugin ha;
    private final PluginManager plugins;
    private final RoutineStore routines;
    private final IdlePersonality idle;
    private volatile String lastRoutine = "";   // how the last routine ended, for listings and /status
    private final Places.Store places;
    /** While a route is being taught: the recording, and what it is for ("kitchen", "kitchen back", "turn-around"). */
    private volatile Places.Recorder recorder;
    private volatile String teaching = "";
    private volatile JSONObject teachExtra = new JSONObject(); // spoken names and area for a new place
    private final CueStore cues;
    private final WatchWithMe watch;

    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long pairUntil;
    private volatile long lastStraightMove;
    private volatile boolean driveSounds;
    private volatile boolean faceActive;
    private volatile long lastManual;        // the last drive or head command from the phone app or web page

    Brain(BrainService io) {
        this.io = io;
        this.sounds = new Sounds(io);
        this.robot = new Robot(io);
        robot.sounds = sounds;
        this.idle = new IdlePersonality(new IdlePersonality.Host() {
            @Override
            public void playSound(int number) {
                sounds.play(number, false);
            }

            @Override
            public void head(int angle) {
                send("head-angle", "angle", angle);
            }
        }, new java.util.Random(), loadMoods());
        idle.setLevel(io.getSharedPreferences("settings", 0).getString("liveliness", IdlePersonality.DEFAULT_LEVEL));
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
                pluginsEvent(Plugin.EVENT_RUNNING);
            }
        });
        this.routines = new RoutineStore(new File(io.getFilesDir(), "routines"), ROUTINE_ACTIONS);
        this.places = new Places.Store(new File(io.getFilesDir(), "places.json"));
        if (places.loadProblem() != null) Log.w(TAG, places.loadProblem());
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
        voice.setExtraPhrases(Places.phrases(places.list()));
        this.assist = new Assist(io, this);
        voice.setStream(assist);
        this.face = new FaceTracker(this);
        this.ha = new HomeAssistantPlugin(new HomeAssistantPlugin.StatusSink() {
            @Override
            public void mqttStatus(String state) {
                try {
                    Brain.this.io.send(new JSONObject().put("agent", "mqtt-status").put("state", state));
                } catch (Exception ignored) {
                }
            }
        });
        watch.setEnabled(io.getSharedPreferences("settings", 0).getBoolean("watch", true));
        this.plugins = new PluginManager(new File(io.getFilesDir(), "plugins.json"), pluginHost,
                Arrays.<Plugin>asList(ha, new BatteryAlertPlugin()));
        robot.addListener(new Robot.Listener() {
            @Override
            public void onRobotChanged() {
                plugins.event(Plugin.EVENT_STATE, null);
            }
        });
        camera.setRawListener(face);
        dlna.start();
        camera.start();
        phone.start();
        voice.start();
        plugins.startAll();
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
                + "\nplaces: " + places.names().size() + ", R2 is at " + places.location()
                + (teaching.isEmpty() ? "" : ", teaching " + teaching)
                + "\nwatch-with-me: " + watchLine()
                + "\n" + idle.status()
                + "\n" + plugins.status();
    }

    // ---- liveliness -----------------------------------------------------------------------

    /** How lively R2 is on his own: "off", "quiet", "chatty" or "full" (IdlePersonality.LEVELS). */
    String liveliness() {
        return idle.level();
    }

    /** Sets and keeps the liveliness level (anything that isn't a level means the default, "quiet"). */
    void setLiveliness(String level) {
        String l = IdlePersonality.normalize(level);
        io.getSharedPreferences("settings", 0).edit().putString("liveliness", l).apply();
        idle.setLevel(l);
        Log.i(TAG, "liveliness " + l);
        robot.changed(); // reports the settings to the agent and Home Assistant
    }

    /** Mood banks from assets/moods.json if the build has one, else the factory sounds whose use is known. */
    private java.util.Map<String, int[]> loadMoods() {
        java.util.Set<Integer> playable = sounds.numbers();
        String json = null;
        try {
            java.io.InputStream in = io.getAssets().open("moods.json");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            json = b.toString("UTF-8");
        } catch (Exception ignored) {
            // optional
        }
        java.util.Map<String, int[]> moods = json == null ? null : IdlePersonality.parseMoods(json, playable);
        if (moods == null || moods.isEmpty()) {
            if (json != null) Log.w(TAG, "assets/moods.json has no playable sounds, using the default moods");
            moods = IdlePersonality.parseMoods(IdlePersonality.DEFAULT_MOODS, playable);
        }
        return moods;
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
            io.send(new JSONObject().put("agent", "running").put("name", seq.running()).put("last_routine", lastRoutine)
                    .put("location", places.location()).put("teaching", teaching));
        } catch (Exception ignored) {
        }
    }

    /** Tells the agent the volume, mute, voice and face settings, for its /status (the web page's controls). */
    private void reportSettings() {
        try {
            io.send(new JSONObject().put("agent", "settings").put("volume", sounds.volume()).put("muted", sounds.muted())
                    .put("chirps", sounds.chirps()).put("voice", robot.voiceRecognition()).put("face", robot.faceDetection())
                    .put("assist", assist.status()).put("liveliness", idle.level()));
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
                ha.mqttSettings(msg);
                break;
            case "plugins": { // GET /plugins, POST /plugin, POST /plugin/config
                JSONObject reply = plugins.request(msg);
                try {
                    io.send(reply.put("agent", "plugins-reply").put("seq", msg.optInt("seq")));
                } catch (Exception ignored) {
                }
                break;
            }
            case "routines":
                if (msg.optString("op").startsWith("cue") || "watch".equals(msg.optString("op"))) onWatchRequest(msg);
                else onRoutinesRequest(msg);
                break;
            case "drove": // a command someone sent through the agent's /serial, e.g. the web page's joystick
                onDrove(msg.optJSONObject("cmd"));
                break;
            case "chirp": // POST /chirp: {"mood"} and/or {"text"}, optional "seed"
                chirp(msg.optString("mood", ""), msg.optString("text", ""),
                        msg.has("seed") ? Long.valueOf(msg.optLong("seed")) : null);
                break;
            case "wifi-list":
            case "wifi-join-result":
                phone.onAgentMessage(msg);
                break;
            default:
                break;
        }
    }

    /**
     * Plays a generated chirp: in a mood (Chirp.MOODS or "random"), or shaped like text (the beeps-only
     * reply; the mood, if given, colors it). seed null: a new one each time. Interrupts the sound playing.
     * Returns false if nothing played (muted, or audio failed).
     */
    boolean chirp(String mood, String text, Long seed) {
        wake();
        if (mood != null && !mood.isEmpty() && !Chirp.knownMood(mood)) {
            Log.w(TAG, "unknown chirp mood " + mood + ", using chatter");
            mood = "chatter";
        }
        long s = seed != null ? seed : System.nanoTime();
        if (text != null && !text.trim().isEmpty()) {
            return sounds.playPcm(Chirp.speak(text, mood, s), true);
        }
        return sounds.chirp(mood == null || mood.isEmpty() ? "random" : mood, s, true);
    }

    private void onButton(int value) {
        Log.i(TAG, "button " + value);
        try {
            plugins.event(Plugin.EVENT_BUTTON, new JSONObject().put("value", value));
        } catch (Exception ignored) {
        }
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
        if (name.startsWith("goto:")) {
            goTo(name.substring(5));
            return;
        }
        switch (name) {
            case "go-home":
                goTo(Places.HOME);
                return;
            case "tour":
                tour();
                return;
            case "stop":
                watch.stop();
                seq.cancel();
                dlna.stopMedia();
                assist.stop();
                stopMotion();
                sounds.ack(S_ACK);
                return;
            case "mute":
                sounds.setMuted(true);
                robot.changed();
                return;
            case "unmute":
                sounds.setMuted(false);
                robot.changed();
                sounds.ack(S_ACK);
                return;
            case "pair":
                if (robot.mode == Robot.PAIR) stopPairing();
                else startPairing();
                return;
            case "voice-on":
            case "voice-off":
                robot.setFlag("voice_recognition", name.endsWith("on")); // the timers apply it
                return;
            case "chirps-on": // the confirmation beep: a new chirp each time (on) or the factory beep (off)
            case "chirps-off":
                sounds.setChirps(name.endsWith("on"));
                sounds.ack(S_ACK);
                return;
            case "face-on":
            case "face-off":
                robot.setFlag("face_detection", name.endsWith("on"));
                return;
            case "liveliness-off":
            case "liveliness-quiet":
            case "liveliness-chatty":
            case "liveliness-full":
                setLiveliness(name.substring("liveliness-".length()));
                return;
            case "phone-app-on":
            case "phone-app-off": {
                boolean on = name.endsWith("on");
                io.getSharedPreferences("settings", 0).edit().putBoolean("phone_app", on).apply();
                phone.setEnabled(on);
                return;
            }
            case "patrol":
                if (seq.isRunning("patrol") || seq.isRunning("tour")) { // a second press ends it
                    action("stop");
                    return;
                }
                // Smarter patrol: tour the taught places instead of wandering, if switched on.
                if (patrolTours() && !places.names().isEmpty() && !Places.UNKNOWN.equals(places.location())) {
                    tour();
                    return;
                }
                break;
            default:
                break;
        }
        if (Places.movingAction(name)) lost("action " + name);
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
                        sounds.ack(S_ACK);
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
                        sounds.ack(S_ACK);
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
            case "chirp": // a new chirp in a random mood
                return new Sequencer.Job() {
                    public void run() {
                        sounds.chirp("random");
                    }
                };
            case "not-recognized": return soundOnly(S_HUH);
            case "lightsaber":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        projectorOff();
                        sounds.ack(S_ACK);
                        send("lightsaber", "power", robot.lightsaber != 0 ? 0 : 1);
                    }
                };
            case "arm":
                return new Sequencer.Job() {
                    public void run() throws Exception {
                        projectorOff();
                        sounds.ack(S_ACK);
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
        if (Places.moves(r)) lost("routine " + name);
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

    /** Saves a routine (the API's and plugins' way); returns what happened. */
    private String saveRoutine(JSONObject j) throws Routine.Invalid {
        String name = j.optString("name");
        for (String a : ACTIONS) {
            if (a.equals(name) && !ROUTINE_ACTIONS.contains(name)) {
                throw new Routine.Invalid("\"" + name + "\" is a reserved action name");
            }
        }
        boolean existed = routines.exists(name);
        Routine r = routines.save(j);
        Log.i(TAG, "routine saved: " + r.name);
        routinesChanged();
        return (existed ? "replaced " : "saved ") + r.name + " (" + r.stepCount() + " steps)"
                + (ROUTINE_ACTIONS.contains(r.name) ? "; it now replaces the built-in " + r.name + " action" : "");
    }

    /** Deletes a routine; returns "deleted ..." or an error. */
    private String deleteRoutine(String name) {
        if (seq.isRunning(name)) seq.cancel();
        if (!routines.delete(name)) return "no routine named " + name;
        routinesChanged();
        return "deleted " + name + (ROUTINE_ACTIONS.contains(name) ? "; the built-in " + name + " action is back" : "");
    }

    private void routinesChanged() {
        if (plugins == null) return;
        try {
            plugins.event(Plugin.EVENT_ROUTINES, new JSONObject().put("names", new JSONArray(routines.names())));
        } catch (Exception ignored) {
        }
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
        public void playChirp(String mood) {
            sounds.chirp(mood);
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
                    reply.put("ok", true).put("result", saveRoutine(j));
                    break;
                }
                case "delete": {
                    String result = deleteRoutine(name);
                    if (result.startsWith("deleted")) reply.put("ok", true).put("result", result);
                    else reply.put("ok", false).put("error", result);
                    break;
                }
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
                    if (!placesRequest(op, name, msg, reply)) reply.put("ok", false).put("error", "unknown op " + op);
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

    // ---- places (docs/places.md) ------------------------------------------------------------

    private boolean patrolTours() {
        return io.getSharedPreferences("settings", 0).getBoolean("patrol_tour", false);
    }

    /** R2 moved in a way no route accounts for, so he no longer knows where he is. */
    private void lost(String why) {
        if (!teaching.isEmpty()) return; // the recording accounts for it
        if (places.setLocation(Places.UNKNOWN)) {
            Log.i(TAG, "location unknown after " + why);
            placesChanged();
        }
    }

    /** A drive command from the phone app or the web page's joystick: recorded while teaching. */
    private void moved(int power, int angle) {
        Places.Recorder r = recorder;
        if (r != null) r.move(System.currentTimeMillis(), power, angle);
        else if (power > 0) lost("driving by hand");
    }

    /** A command sent through the agent's /serial (the web page): driving there counts like the phone app's. */
    private void onDrove(JSONObject cmd) {
        if (cmd == null) return;
        String c = cmd.optString("cmd");
        if ("move".equals(c)) {
            if (!"idle".equals(seq.running()) && cmd.optInt("power") > 0) seq.cancel(); // the joystick takes over
            moved(cmd.optInt("power"), cmd.optInt("angle"));
        } else if ("mode".equals(c) && Places.movingMode(cmd.optInt("mode"))) {
            lost("controller mode " + cmd.optInt("mode"));
        }
    }

    /** Voice phrases, Home Assistant's place list and the agent's /status follow the places. */
    private void placesChanged() {
        voice.setExtraPhrases(Places.phrases(places.list()));
        if (plugins != null) plugins.event(Plugin.EVENT_STATE, null);
        reportRunning();
    }

    private boolean tripRunning() {
        String r = seq.running();
        return r.startsWith("go-to-") || "tour".equals(r);
    }

    /** Sends R2 to a place (or home). Returns an error, or null if he's on his way (or already there). */
    String goTo(String to) {
        wake();
        if (!teaching.isEmpty()) return refuse(to, "I'm learning a route (" + teaching + "); save or cancel it first");
        if (tripRunning()) seq.cancel(); // stopped part way: he no longer knows where he is
        Places.Trip t = Places.plan(places.location(), to, places.map(), places.turnAround());
        if (t.error != null) return refuse(to, t.error);
        if (t.segments.isEmpty()) {
            sounds.play(S_ACK, true);
            lastRoutine = "go-to-" + to + ": already there";
            reportRunning();
            return null;
        }
        runTrip("go-to-" + to, Places.trip(to, t), t.end);
        return null;
    }

    /** Smarter patrol: visit each taught place in turn, then come home. */
    String tour() {
        wake();
        if (!teaching.isEmpty()) return refuse("tour", "I'm learning a route; save or cancel it first");
        if (tripRunning()) seq.cancel();
        Places.Trip t = Places.tour(places.location(), places.map(), places.turnAround());
        if (t.error != null) return refuse("tour", t.error);
        runTrip("tour", Places.trip("tour", t), t.end);
        return null;
    }

    private String refuse(String to, String why) {
        Log.i(TAG, "go to " + to + ": " + why);
        lastRoutine = "go-to-" + to + ": not started, " + why;
        reportRunning();
        sounds.play(S_HUH, true);
        return why;
    }

    private void runTrip(final String name, final Routine r, final String end) {
        Log.i(TAG, "trip " + name + ": " + r.description);
        lost("leaving on " + name); // until he arrives
        final RoutineRunner runner = new RoutineRunner(routineHost, RoutineRunner.REAL_CLOCK);
        seq.start(name, new Sequencer.Job() {
            public void run() throws Exception {
                try {
                    runner.run(r);
                    places.setLocation(end);
                    lastRoutine = name + ": arrived at " + end;
                    placesChanged();
                } catch (RoutineRunner.Abort a) {
                    lastRoutine = name + ": stopped, " + a.getMessage();
                    Log.w(TAG, "trip " + lastRoutine);
                    stopMotion();
                } catch (InterruptedException cancelled) {
                    lastRoutine = name + ": cancelled";
                    throw cancelled;
                }
            }
        });
    }

    /**
     * Starts teaching: what = "there" (drive from home to the place), "back" (from the place home) or
     * "turn" (spin R2 around once on the spot, for mirrored ways back). Driving by hand is recorded until
     * teachSave or teachCancel.
     */
    private String teach(String name, String what, JSONObject extra) throws Routine.Invalid {
        if ("turn".equals(what)) {
            name = "turn-around";
        } else {
            if (!Places.validName(name)) throw new Routine.Invalid("place name must be 1-32 characters: a-z, 0-9 and -, and not home or unknown");
            if ("back".equals(what) && places.get(name) == null) throw new Routine.Invalid("no place named " + name + "; teach the way there first");
            if (!"there".equals(what) && !"back".equals(what)) throw new Routine.Invalid("what= must be there, back or turn");
            if ("there".equals(what) && places.get(name) == null && places.names().size() >= Places.MAX_PLACES) {
                throw new Routine.Invalid("already " + Places.MAX_PLACES + " places; delete some first");
            }
        }
        seq.cancel();
        stopMotion();
        teachExtra = extra;
        recorder = new Places.Recorder();
        teaching = "there".equals(what) ? name : "back".equals(what) ? name + " back" : name;
        Log.i(TAG, "teaching " + teaching);
        sounds.play(S_ACK, true);
        reportRunning();
        return "there".equals(what) ? "R2 is at home now. Drive him to " + name + ", then save."
                : "back".equals(what) ? "R2 is at " + name + " now. Drive him home, then save."
                : "Spin R2 around once on the spot (half a turn), then save.";
    }

    private String teachSave() throws Exception {
        Places.Recorder r = recorder;
        String what = teaching;
        if (r == null) throw new Routine.Invalid("not teaching anything");
        List<Places.Segment> segs = r.finish(System.currentTimeMillis());
        if (r.problem() != null) throw new Routine.Invalid(r.problem());
        if (segs.isEmpty()) throw new Routine.Invalid("nothing was recorded: drive R2 with the joystick first");
        String result;
        if ("turn-around".equals(what)) {
            places.setTurnAround(segs);
            result = "saved the turn-around (" + Places.seconds(segs) + " s)";
        } else if (what.endsWith(" back")) {
            String name = what.substring(0, what.length() - 5);
            Places.Place p = places.get(name);
            if (p == null) throw new Routine.Invalid("place " + name + " was deleted");
            places.put(p.withBack(Places.parseSegments(Places.segmentsJson(segs), "back", true)));
            places.setLocation(Places.HOME);
            result = "saved the way back from " + name + " (" + Places.seconds(segs) + " s); R2 is home";
        } else {
            Places.Place old = places.get(what);
            JSONObject j = new JSONObject().put("name", what).put("there", Places.segmentsJson(segs));
            JSONObject x = teachExtra;
            if (x.has("spoken")) j.put("spoken", x.get("spoken"));
            else if (old != null) j.put("spoken", new JSONArray(old.spoken));
            if (x.has("area")) j.put("area", x.get("area"));
            else if (old != null && !old.area.isEmpty()) j.put("area", old.area);
            if (old != null && old.marker >= 0) j.put("marker", old.marker);
            // A new way there makes a taught way back wrong, so it is dropped (mirroring follows the new route).
            places.put(Places.parsePlace(j));
            places.setLocation(what);
            result = "saved " + what + " (" + segs.size() + " moves, " + Places.seconds(segs) + " s); R2 is at " + what
                    + (old != null && !old.back.isEmpty() ? ". Its taught way back was dropped; teach it again" : "");
        }
        recorder = null;
        teaching = "";
        sounds.play(S_REPLY, true);
        placesChanged();
        return result;
    }

    private String teachCancel() {
        if (recorder == null) return "not teaching anything";
        String was = teaching;
        recorder = null;
        teaching = "";
        stopMotion();
        lost("cancelled teaching");
        reportRunning();
        return "cancelled teaching " + was + "; R2 doesn't know where he is now";
    }

    /** The places part of the agent's routine API (ops place-*, goto, tour, here, teach-*). False: not a places op. */
    private boolean placesRequest(String op, String name, JSONObject msg, JSONObject reply) throws Exception {
        switch (op) {
            case "places": {
                JSONArray list = new JSONArray();
                for (Places.Place p : places.list()) list.put(p.summary());
                reply.put("ok", true).put("places", list).put("location", places.location()).put("teaching", teaching)
                        .put("turn_around_seconds", Places.seconds(places.turnAround()))
                        .put("charger_is_home", places.chargerIsHome()).put("patrol_tour", patrolTours())
                        .put("running", seq.running()).put("last_trip", lastRoutine);
                if (places.loadProblem() != null) reply.put("problem", places.loadProblem());
                return true;
            }
            case "place-get": {
                Places.Place p = places.get(name);
                if (p == null) reply.put("ok", false).put("error", "no place named " + name);
                else reply.put("ok", true).put("place", p.json());
                return true;
            }
            case "place-put": {
                JSONObject j = msg.optJSONObject("place");
                if (j == null) throw new Routine.Invalid("send the place as a JSON object");
                Places.Place p = Places.parsePlace(j);
                boolean existed = places.get(p.name) != null;
                places.put(p);
                placesChanged();
                reply.put("ok", true).put("result", (existed ? "replaced " : "saved ") + p.name);
                return true;
            }
            case "place-delete":
                if (places.delete(name)) {
                    placesChanged();
                    reply.put("ok", true).put("result", "deleted " + name);
                } else {
                    reply.put("ok", false).put("error", "no place named " + name);
                }
                return true;
            case "goto": {
                String err = goTo(name);
                if (err == null) reply.put("ok", true).put("result", "going to " + name);
                else reply.put("ok", false).put("error", err);
                return true;
            }
            case "tour": {
                String err = tour();
                if (err == null) reply.put("ok", true).put("result", "touring the places");
                else reply.put("ok", false).put("error", err);
                return true;
            }
            case "here": // "you're home": tells R2 where he is now (home, a place, or unknown)
                if (!Places.HOME.equals(name) && !Places.UNKNOWN.equals(name) && places.get(name) == null) {
                    reply.put("ok", false).put("error", "no place named " + name);
                    return true;
                }
                if (tripRunning()) seq.cancel();
                places.setLocation(name);
                placesChanged();
                reply.put("ok", true).put("result", "R2 is at " + places.location());
                return true;
            case "teach":
                reply.put("ok", true).put("result", teach(name, msg.optString("what", "there"), msg.optJSONObject("extra") == null
                        ? new JSONObject() : msg.optJSONObject("extra")));
                return true;
            case "teach-save":
                reply.put("ok", true).put("result", teachSave());
                return true;
            case "teach-cancel":
                reply.put("ok", true).put("result", teachCancel());
                return true;
            case "place-settings": {
                if (msg.has("charger_is_home")) places.setChargerIsHome(msg.optBoolean("charger_is_home"));
                if (msg.has("patrol_tour")) io.getSharedPreferences("settings", 0).edit().putBoolean("patrol_tour", msg.optBoolean("patrol_tour")).apply();
                reply.put("ok", true).put("result", "charger_is_home " + places.chargerIsHome() + ", patrol_tour " + patrolTours());
                return true;
            }
            default:
                return false;
        }
    }

    /** The factory turns: an acknowledgement, then the controller's own movement. */
    private Sequencer.Job controllerMove(final int mode) {
        return new Sequencer.Job() {
            public void run() throws Exception {
                sounds.ack(S_ACK);
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
                sounds.ack(S_ACK);
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
        sounds.ack(S_ACK);
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
                    long sleepAfter = "full".equals(liveliness()) ? SLEEP_AFTER_FULL_MS : SLEEP_AFTER_MS;
                    if (robot.mode == Robot.READY && now - lastActivity > sleepAfter) {
                        Log.i(TAG, "sleeping after " + sleepAfter / 1000 + " s idle");
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
                    if (robot.charging > 0 && places.chargerIsHome() && teaching.isEmpty() && places.setLocation(Places.HOME)) {
                        Log.i(TAG, "on the charger: R2 is home");
                        placesChanged();
                    }
                    watch.tick(android.os.SystemClock.elapsedRealtime());
                    if (watch.active()) lastActivity = now; // watching a film together: stay awake
                    voice.setEnabled(robot.voiceRecognition());
                    // Idle chatter only while awake and nothing else is going on (it doesn't keep R2 awake).
                    boolean idleNow = robot.mode == Robot.READY && "idle".equals(seq.running()) && !driveSounds
                            && now - lastManual > 5_000 && !dlna.playing() && !assist.speaking()
                            && robot.assistState == Assist.IDLE && !robot.listening && !face.tracking() && !watch.active();
                    idle.tick(now, idleNow, sounds.isPlaying());
                    if (robot.mode == Robot.PAIR && now > pairUntil) {
                        Log.i(TAG, "pairing mode ended");
                        stopPairing();
                    }
                    // Factory: while driving straight, a droid sound every other 2 s beat, alternating two sounds.
                    // Activity "off" keeps them quiet.
                    if (driveSounds && (now - lastStraightMove > 1000 || "off".equals(liveliness()))) driveSounds = false;
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
            if (plugins != null) plugins.event(Plugin.EVENT_STATE, null);
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

    // ---- plugins --------------------------------------------------------------------------

    private void pluginsEvent(String type) {
        if (plugins == null) return; // during startup
        try {
            plugins.event(type, new JSONObject().put("name", seq.running()).put("last_routine", lastRoutine));
            plugins.event(Plugin.EVENT_STATE, null); // "action" is part of the state
        } catch (Exception ignored) {
        }
    }

    /** What plugins can reach; PluginContext checks their permissions before calling any of it. */
    private final PluginContext.Host pluginHost = new PluginContext.Host() {
        @Override
        public JSONObject state() {
            JSONObject s = new JSONObject();
            try {
                int m = robot.mode;
                s.put("battery", robot.battery).put("charging", robot.charging)
                        .put("lightsaber", robot.lightsaber != 0).put("arm", robot.arm != 0)
                        .put("projector", robot.projector).put("lcd_s", robot.lcdS >= 2).put("lcd_l", robot.lcdL >= 2)
                        .put("mode", m >= 1 && m <= HomeAssistant.MODES.length ? HomeAssistant.MODES[m - 1] : "ready")
                        .put("action", seq.running()).put("error", robot.error).put("location", places.location())
                        .put("muted", sounds.muted()).put("volume", sounds.volume())
                        .put("voice", robot.voiceRecognition()).put("face", robot.faceDetection())
                        .put("liveliness", liveliness())
                        .put("name", robot.name()).put("uuid", robot.uuid()).put("version", BuildInfo.VERSION)
                        .put("watch", watch.status(android.os.SystemClock.elapsedRealtime()));
            } catch (Exception ignored) {
            }
            return s;
        }

        @Override
        public boolean isAction(String name) {
            return Arrays.asList(ACTIONS).contains(name);
        }

        @Override
        public void action(String name) {
            Brain.this.action(name);
        }

        @Override
        public void stop() {
            Brain.this.action("stop");
        }

        @Override
        public void projector(int mode) {
            Brain.this.projector(mode);
        }

        @Override
        public java.util.List<String> routineNames() {
            return routines.names();
        }

        @Override
        public String runRoutine(String name) {
            return routines.exists(name) ? Brain.this.runRoutine(name) : "no routine named " + name;
        }

        @Override
        public String saveRoutine(JSONObject routine) throws Routine.Invalid {
            return Brain.this.saveRoutine(routine);
        }

        @Override
        public String deleteRoutine(String name) {
            String r = Brain.this.deleteRoutine(name);
            return r.startsWith("deleted") ? null : r;
        }

        @Override
        public void controller(JSONObject cmd) {
            wake();
            io.send(cmd);
        }

        @Override
        public void playSound(int number) {
            sounds.play(number, true);
        }

        @Override
        public void setVolume(int percent) {
            sounds.setVolume(percent);
        }

        @Override
        public void led(int r, int b, int y, int g) {
            try {
                io.send(new JSONObject().put("cmd", "led").put("r", r).put("b", b).put("y", y).put("g", g));
            } catch (Exception ignored) {
            }
        }

        @Override
        public java.util.List<String> placeNames() {
            return places.names();
        }

        @Override
        public void goTo(String place) {
            Brain.this.goTo(place);
        }

        @Override
        public String watchUpdate(JSONObject msg) {
            return watch.update(msg, true, android.os.SystemClock.elapsedRealtime());
        }

        @Override
        public void setWatch(boolean on) {
            setWatchEnabled(on);
        }

        @Override
        public void log(String msg) {
            Log.i(TAG, msg);
        }
    };

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
        if (sound) sounds.ack(S_ACK); // a short chirp: R2 heard "hey R2-D2" and is listening
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
        moved(power, angle);
        lastManual = System.currentTimeMillis();
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
        lastManual = System.currentTimeMillis();
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
