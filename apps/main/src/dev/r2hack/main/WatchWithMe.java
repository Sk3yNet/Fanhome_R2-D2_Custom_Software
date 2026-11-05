package dev.r2hack.main;

import java.util.Locale;
import org.json.JSONObject;

/**
 * Watch-with-me: R2 reacts to a film while you watch it together. A cue file (CueFile) lists the
 * reactions and when they happen; this class keeps track of where the film is and fires each cue as
 * the position passes it. The position comes from Home Assistant (a media player's position, sent over
 * MQTT by an automation) or from the API (POST /watch, e.g. the PC tool's watch-start).
 *
 * Between updates the position is counted on from the last one. A jump of more than SEEK_S either way
 * is a seek: the cues in between are skipped, and after seeking back they fire again. A cue noticed more
 * than LATE_S after its time is skipped rather than played late. Updates from Home Assistant must keep
 * coming (the automation sends one every 30 s while playing); after STALE_MS without one R2 assumes he
 * has lost track and goes quiet until the next update.
 *
 * Plain Java and org.json only, so it can be tested off the robot (apps/test.sh). Times are in
 * milliseconds of a monotonic clock handed in by the caller.
 */
final class WatchWithMe {
    interface Host {
        /** A stored cue file by name, or null (missing or invalid). */
        CueFile loadCue(String name);

        /** The cue file whose match list fits a media title, or null. */
        CueFile findCue(String title);

        /** Runs one cue's reaction (the host decides whether R2 is free to). Called without locks held. */
        void fire(CueFile file, CueFile.Cue cue);

        void log(String msg);

        /** The state, cue file or last reaction changed (for Home Assistant). Called without locks held. */
        void changed();
    }

    static final double SEEK_S = 4;
    static final double LATE_S = 3;
    static final long STALE_MS = 90_000;

    /** Player states that mean nothing is playing (Home Assistant's media_player states, plus "stopped"). */
    static final String[] OFF_STATES = {"off", "idle", "on", "standby", "stopped", "unavailable", "unknown", "none"};
    static final String[] PAUSED_STATES = {"paused", "buffering"};

    private final Host host;
    private boolean enabled = true;
    private CueFile cue;
    private String title = "";
    private String phase = "off";       // off, playing, paused, no cue file, lost sync
    private boolean playing;
    private boolean fromHa;
    private double anchorPos;           // seconds into the film at anchorAt
    private long anchorAt;
    private double done;                // cues up to this position have fired (or were skipped)
    private long lastUpdate;
    private String last = "";           // the last reaction, e.g. "0:42:10 gasp"
    private int skipped;

    WatchWithMe(Host host) {
        this.host = host;
    }

    synchronized boolean enabled() {
        return enabled;
    }

    /** The on/off switch (Home Assistant's "Watch with me" switch). Off: the position is still followed, nothing fires. */
    void setEnabled(boolean on) {
        synchronized (this) {
            if (enabled == on) return;
            enabled = on;
        }
        host.log("watch-with-me " + (on ? "on" : "off"));
        host.changed();
    }

    /** Whether a film is playing with a cue file (R2 stays awake then). */
    synchronized boolean active() {
        return enabled && cue != null && playing;
    }

    /**
     * A player update: {"state": "playing|paused|off|...", "position": seconds or "1:02:03", "title": "...",
     * "cue": "<cue file>"}. Without cue, the title picks the file (its match list). Returns what happened;
     * throws IllegalArgumentException for a bad update (the API answers 400).
     */
    String update(JSONObject msg, boolean fromHomeAssistant, long now) {
        String state = msg.optString("state", "").trim().toLowerCase(Locale.US);
        if (state.isEmpty()) throw new IllegalArgumentException("state is needed: playing, paused or off");
        boolean off = Routine.contains(OFF_STATES, state);
        boolean play = "playing".equals(state);
        if (!off && !play && !Routine.contains(PAUSED_STATES, state)) {
            throw new IllegalArgumentException("unknown state \"" + state + "\" (playing, paused, buffering, or off/idle/stopped)");
        }
        double pos = position(msg);
        String newTitle = msg.isNull("title") ? "" : msg.optString("title", "").trim();
        String cueName = msg.isNull("cue") ? "" : msg.optString("cue", "").trim();
        String result;
        boolean changed;
        synchronized (this) {
            String before = signature();
            lastUpdate = now;
            fromHa = fromHomeAssistant;
            if (off) {
                result = cue == null && "off".equals(phase) ? "nothing playing" : "stopped watching" + (cue != null ? " " + cue.name : "");
                reset("off");
            } else {
                CueFile next = cue;
                if (!cueName.isEmpty()) {
                    if (cue == null || !cue.name.equals(cueName)) next = host.loadCue(cueName);
                    if (next == null) {
                        reset("no cue file");
                        throw new IllegalArgumentException("no usable cue file named " + cueName + " (see GET /cues)");
                    }
                } else if (!newTitle.isEmpty() && (cue == null || !newTitle.equals(title))) {
                    next = cue != null && cue.matches(newTitle) ? cue : host.findCue(newTitle);
                } else if (cue == null) {
                    next = null;
                }
                if (next == null) {
                    reset("no cue file");
                    title = newTitle;
                    result = newTitle.isEmpty() ? "no cue file: send cue, or a title that a cue file's match list fits"
                            : "no cue file matches \"" + newTitle + "\"";
                } else {
                    if (cue == null || !cue.name.equals(next.name)) {
                        cue = next;
                        anchorPos = Double.isNaN(pos) ? 0 : pos;
                        anchorAt = now;
                        done = anchorPos;
                        last = "";
                        skipped = 0;
                        host.log("watch-with-me: " + next.name + " from " + CueFile.clock(anchorPos));
                    } else {
                        double expected = current(now);
                        if (!Double.isNaN(pos)) {
                            if (Math.abs(pos - expected) > SEEK_S) {
                                host.log("watch-with-me: jumped from " + CueFile.clock(expected) + " to " + CueFile.clock(pos));
                                done = pos;
                            }
                            anchorPos = pos;
                            anchorAt = now;
                        } else if (playing != play) {
                            anchorPos = expected;
                            anchorAt = now;
                        }
                    }
                    if (!newTitle.isEmpty()) title = newTitle;
                    playing = play;
                    phase = play ? "playing" : "paused";
                    result = phase + " " + cue.name + " at " + CueFile.clock(current(now)) + (enabled ? "" : " (watch-with-me is switched off)");
                }
            }
            changed = !before.equals(signature());
        }
        if (changed) host.changed();
        return result;
    }

    /** Called often (every 250 ms): fires the cue whose time has just passed. */
    void tick(long now) {
        CueFile file;
        CueFile.Cue pick = null;
        boolean changed = false;
        synchronized (this) {
            if (cue == null || !playing) return;
            if (fromHa && now - lastUpdate > STALE_MS) {
                host.log("watch-with-me: no update from Home Assistant for " + STALE_MS / 1000 + " s, pausing");
                playing = false;
                phase = "lost sync";
                changed = true;
                file = null;
            } else {
                file = cue;
                double pos = current(now);
                if (pos <= done) return;
                for (CueFile.Cue c : cue.cues) {
                    double t = cue.time(c);
                    if (t <= done) continue;
                    if (t > pos) break;
                    if (pos - t <= LATE_S && enabled) {
                        if (pick != null) skipped++; // two in one tick: the later one wins
                        pick = c;
                    } else if (enabled) {
                        skipped++;
                    }
                }
                done = pos;
                if (pick != null) {
                    last = CueFile.clock(cue.time(pick)) + " " + pick.what() + (pick.note.isEmpty() ? "" : " (" + pick.note + ")");
                    changed = true;
                }
            }
        }
        if (pick != null) {
            host.log("watch-with-me: " + last);
            host.fire(file, pick);
        }
        if (changed) host.changed();
    }

    /** Ends the session (the stop action, or the cue file was deleted). */
    void stop() {
        boolean changed;
        synchronized (this) {
            changed = cue != null || !"off".equals(phase);
            reset("off");
        }
        if (changed) host.changed();
    }

    synchronized String cueName() {
        return cue != null ? cue.name : "";
    }

    /** For GET /watch and Home Assistant: {enabled, state, cue, title, position, next, last, source}. */
    synchronized JSONObject status(long now) {
        JSONObject s = new JSONObject();
        try {
            s.put("enabled", enabled).put("state", phase).put("cue", cue != null ? cue.name : "").put("title", title)
                    .put("last", last).put("skipped", skipped);
            if (cue != null) {
                double pos = current(now);
                s.put("position", Math.round(pos * 10) / 10.0).put("position_text", CueFile.clock(pos))
                        .put("source", fromHa ? "home assistant" : "api");
                for (CueFile.Cue c : cue.cues) {
                    double t = cue.time(c);
                    if (t > done) {
                        s.put("next", new JSONObject().put("at", CueFile.clock(t)).put("what", c.what()).put("note", c.note));
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    /** What Home Assistant shows: changes on state, file and reactions, not with every second of the film. */
    private String signature() {
        return enabled + "|" + phase + "|" + (cue != null ? cue.name : "") + "|" + title + "|" + last;
    }

    private void reset(String newPhase) {
        cue = null;
        playing = false;
        phase = newPhase;
        title = "";
        last = "";
        skipped = 0;
    }

    private double current(long now) {
        return playing ? anchorPos + (now - anchorAt) / 1000.0 : anchorPos;
    }

    /** position as seconds, from a number or a "1:02:03" string; NaN if missing or null. */
    private static double position(JSONObject msg) {
        if (!msg.has("position") || msg.isNull("position")) return Double.NaN;
        Object v = msg.opt("position");
        try {
            double p = v instanceof String && !((String) v).contains(":")
                    ? Double.parseDouble(((String) v).trim()) : CueFile.time(v, "position");
            if (Double.isNaN(p) || p < 0 || p > CueFile.MAX_AT_S) throw new IllegalArgumentException("position " + v + " is outside 0 to 12 hours");
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("position: seconds, or a time like \"1:02:03\", not " + v);
        } catch (Routine.Invalid e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
