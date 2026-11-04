package dev.r2hack.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONObject;

/**
 * R2 as a Home Assistant voice satellite (Wyoming protocol), so any Assist pipeline, including an AI
 * conversation agent, can answer through R2. Home Assistant's Wyoming integration connects to this
 * server (TCP port 10700, found by zeroconf as _wyoming._tcp), and R2:
 *  - streams 16 kHz microphone audio to the pipeline, and
 *  - plays the spoken reply (and Home Assistant announcements) through its speaker.
 *
 * Two modes, kept across reboots:
 *  - ROBOT_WAKE: "hey R2-D2" is spotted on the robot (Voice); only what follows it leaves the robot,
 *    until Home Assistant has the transcript (or after 15 s).
 *  - HA_WAKE: R2 streams all microphone audio while Home Assistant is connected, and the pipeline's
 *    own wake word (openWakeWord) starts each request.
 * If Home Assistant isn't connected, the on-robot voice commands work as before.
 *
 * Wire format: a JSON header line {"type", "data_length", "payload_length", "version"}, then
 * data_length bytes of JSON data, then payload_length bytes of raw audio.
 */
final class Assist implements Voice.Stream {
    interface Host {
        /** The pipeline started listening: wake R2 up, and chirp if sound=true. */
        void onAssistWake(boolean sound);

        /** IDLE, LISTENING, THINKING or SPEAKING, for the back light. */
        void onAssistState(int state);

        /** Mode, connection or state changed (for the agent's /status). */
        void onAssistStatus();

        /** The pipeline failed (nothing understood, agent unreachable...). */
        void onAssistError(String text);

        /** True while R2 is playing one of its own sounds (the mic is ignored meanwhile). */
        boolean playingSound();

        String robotName();
    }

    /** Plays the reply audio. Separate so the protocol can be tested without Android audio. */
    interface Speaker {
        void open(int rate, int width, int channels) throws Exception;

        void write(byte[] pcm);

        /** Blocks until everything written has played. */
        void drain() throws InterruptedException;

        void abort();
    }

    static final int PORT = 10700;
    static final int OFF = 0, ROBOT_WAKE = 1, HA_WAKE = 2;
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;
    static final String WAKE_NAME = "hey_r2d2";
    private static final String[] MODE_NAMES = {"off", "robot", "ha"};
    private static final String TAG = "R2Assist";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String VERSION = "1.5.4";
    private static final int RATE = 16000;
    private static final long MAX_REQUEST_MS = 15_000;   // robot wake: stop streaming after this
    private static final long MAX_THINKING_MS = 15_000;  // no reply audio after this: lights back to idle
    private static final long REPLY_GAP_MS = 10_000;     // reply audio stopped arriving
    private static final long RERUN_AFTER_ERROR_MS = 5_000;
    private static final int MAX_HEADER = 64 * 1024, MAX_DATA = 1024 * 1024, MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final Object END = new Object();

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Host host;
    private final Speaker speaker;
    private final int port;
    private final CopyOnWriteArrayList<Conn> conns = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Object> playQueue = new LinkedBlockingQueue<>();

    private volatile int mode;
    private volatile String pinnedHost;
    private volatile Conn active;           // the connection that sent run-satellite
    private volatile boolean streaming;     // mic audio goes to the active connection
    private volatile long streamStarted, streamTimestamp;
    private volatile int state = IDLE;
    private volatile long stateSince;
    private volatile boolean speaking;
    private volatile long lastReplyAudio;
    private volatile Conn speakingTo;
    private volatile long rerunAt;
    private volatile String lastHeard = "", lastReply = "", lastError = "";
    private ServerSocket server;
    private Object nsdListener;

    /** On the robot. */
    Assist(Context ctx, Host host) {
        this(ctx, host, new TrackSpeaker(), PORT);
    }

    /** ctx may be null (tests): settings are then kept in memory only and nothing is advertised. */
    Assist(Context ctx, Host host, Speaker speaker, int port) {
        this.ctx = ctx;
        this.prefs = ctx != null ? ctx.getSharedPreferences("assist", Context.MODE_PRIVATE) : null;
        this.host = host;
        this.speaker = speaker;
        this.port = port;
        this.mode = prefs != null ? prefs.getInt("mode", OFF) : OFF;
        this.pinnedHost = prefs != null ? prefs.getString("host", "") : "";
    }

    void start() {
        daemon("assist-server", new Runnable() {
            public void run() {
                serverLoop();
            }
        });
        daemon("assist-player", new Runnable() {
            public void run() {
                playerLoop();
            }
        });
        daemon("assist-timers", new Runnable() {
            public void run() {
                timerLoop();
            }
        });
    }

    /**
     * mode: off | robot | ha ("" or null keeps it). host: only this address may connect
     * ("" = anyone on R2's subnet, null keeps it).
     */
    String configure(String modeName, String onlyHost) {
        int m = modeName == null || modeName.isEmpty() ? mode : -1;
        for (int i = 0; i < MODE_NAMES.length; i++) if (MODE_NAMES[i].equals(modeName)) m = i;
        if (m < 0) return "mode must be off, robot or ha";
        if (onlyHost != null) {
            onlyHost = onlyHost.trim();
            if (!onlyHost.isEmpty() && !onlyHost.matches("[0-9a-fA-F.:]{2,45}")) return "host must be an IP address";
            pinnedHost = onlyHost;
        }
        mode = m;
        if (prefs != null) prefs.edit().putInt("mode", m).putString("host", pinnedHost).apply();
        Log.i(TAG, "mode " + MODE_NAMES[m] + (pinnedHost.isEmpty() ? "" : ", only " + pinnedHost));
        // Start over: Home Assistant reconnects within a few seconds and gets the new mode.
        for (Conn c : conns) c.close();
        host.onAssistStatus();
        return status();
    }

    String status() {
        if (mode == OFF) return "assist: off";
        Conn a = active;
        String s = "assist: " + (mode == ROBOT_WAKE ? "wake word on the robot" : "wake word in Home Assistant")
                + (pinnedHost.isEmpty() ? "" : ", only " + pinnedHost) + ", ";
        if (a == null) return s + "waiting for Home Assistant on port " + port;
        String[] names = {"idle", "listening", "thinking", "speaking"};
        return s + "connected to " + a.peer + ", " + names[state]
                + (lastHeard.isEmpty() ? "" : ", last heard \"" + lastHeard + "\"")
                + (lastError.isEmpty() ? "" : ", last error: " + lastError);
    }

    int mode() {
        return mode;
    }

    boolean connected() {
        return active != null;
    }

    boolean speaking() {
        return speaking;
    }

    /** The stop action: no more reply, no more listening. */
    void stop() {
        abortReply();
        Conn a = active;
        if (streaming && mode == ROBOT_WAKE && a != null) endStream(a);
        setState(IDLE);
    }

    // ---- Voice.Stream ------------------------------------------------------------------

    @Override
    public boolean takeWake() {
        Conn a = active;
        if (mode != ROBOT_WAKE || a == null || speaking) return false;
        lastHeard = "";
        try {
            a.send("detection", new JSONObject().put("name", WAKE_NAME).put("timestamp", 0), null);
            a.send("run-pipeline", new JSONObject().put("start_stage", "asr").put("end_stage", "tts")
                    .put("restart_on_end", false).put("wake_word_name", WAKE_NAME), null);
            beginAudio(a);
        } catch (Exception e) {
            a.close();
            return false;
        }
        host.onAssistWake(true);
        setState(LISTENING);
        return true;
    }

    @Override
    public boolean wantsAudio() {
        return streaming && !speaking;
    }

    @Override
    public void audio(short[] buf, int n) {
        Conn a = active;
        if (!streaming || a == null || speaking) return;
        if (host.playingSound()) {
            streamTimestamp += n * 1000L / RATE; // R2's own chirp: don't send it, keep the clock going
            return;
        }
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            pcm[2 * i] = (byte) buf[i];
            pcm[2 * i + 1] = (byte) (buf[i] >> 8);
        }
        try {
            a.send("audio-chunk", format().put("timestamp", streamTimestamp), pcm);
        } catch (Exception e) {
            a.close();
        }
        streamTimestamp += n * 1000L / RATE;
    }

    // ---- streaming -----------------------------------------------------------------------

    private static JSONObject format() throws Exception {
        return new JSONObject().put("rate", RATE).put("width", 2).put("channels", 1);
    }

    private void beginAudio(Conn a) throws Exception {
        streamTimestamp = 0;
        streamStarted = System.currentTimeMillis();
        a.send("audio-start", format().put("timestamp", 0), null);
        streaming = true;
    }

    /** HA_WAKE: one pipeline that Home Assistant restarts after each request, fed by a continuous stream. */
    private void runContinuous(Conn a) {
        try {
            a.send("run-pipeline", new JSONObject().put("start_stage", "wake").put("end_stage", "tts")
                    .put("restart_on_end", true), null);
            beginAudio(a);
        } catch (Exception e) {
            a.close();
        }
    }

    /** ROBOT_WAKE: the request is over (transcript, end of speech, timeout). */
    private void endStream(Conn a) {
        if (!streaming || mode != ROBOT_WAKE) return;
        streaming = false;
        try {
            a.send("audio-stop", new JSONObject().put("timestamp", streamTimestamp), null);
        } catch (Exception e) {
            a.close();
        }
    }

    private void setState(int s) {
        if (state == s) return;
        state = s;
        stateSince = System.currentTimeMillis();
        host.onAssistState(s);
        host.onAssistStatus();
    }

    // ---- events from Home Assistant --------------------------------------------------------

    private void onEvent(Conn c, String type, JSONObject data, byte[] payload) throws Exception {
        boolean isActive = c == active;
        switch (type) {
            case "describe":
                c.send("info", info(), null);
                break;
            case "run-satellite":
                activate(c);
                break;
            case "pause-satellite":
                if (isActive) deactivate(c);
                break;
            case "ping":
                c.send("pong", new JSONObject().put("text", data.optString("text", "")), null);
                break;
            case "detection": // Home Assistant's wake word (HA_WAKE)
                if (isActive) {
                    lastHeard = "";
                    host.onAssistWake(false);
                    setState(LISTENING);
                }
                break;
            case "transcribe":
                if (isActive && state != LISTENING) setState(LISTENING);
                break;
            case "voice-stopped":
                if (isActive) endStream(c);
                break;
            case "transcript":
                if (isActive) {
                    lastHeard = clip(data.optString("text"));
                    Log.i(TAG, "heard \"" + lastHeard + "\"");
                    endStream(c);
                    setState(THINKING);
                }
                break;
            case "synthesize":
                if (isActive) lastReply = clip(data.optString("text"));
                break;
            case "audio-start":
                if (isActive) startReply(c, data);
                break;
            case "audio-chunk":
                if (isActive && speaking && speakingTo == c && payload != null) {
                    lastReplyAudio = System.currentTimeMillis();
                    playQueue.add(payload);
                }
                break;
            case "audio-stop":
                if (isActive && speaking && speakingTo == c) playQueue.add(END);
                break;
            case "error":
                if (isActive) {
                    lastError = clip(data.optString("text"));
                    Log.w(TAG, "pipeline error: " + lastError + " (" + data.optString("code") + ")");
                    endStream(c);
                    setState(IDLE);
                    host.onAssistError(lastError);
                    if (mode == HA_WAKE) {
                        streaming = false;
                        rerunAt = System.currentTimeMillis() + RERUN_AFTER_ERROR_MS;
                    }
                }
                break;
            default: // timers, voice-started, pong...
                break;
        }
    }

    private void activate(Conn c) {
        Conn old = active;
        if (old != null && old != c) old.close();
        active = c;
        streaming = false;
        rerunAt = 0;
        lastError = "";
        Log.i(TAG, "Home Assistant connected from " + c.peer);
        if (mode == HA_WAKE) runContinuous(c);
        host.onAssistStatus();
    }

    private void deactivate(Conn c) {
        if (active != c) return;
        active = null;
        streaming = false;
        if (speakingTo == c) abortReply();
        setState(IDLE);
        Log.i(TAG, "Home Assistant paused or disconnected (" + c.peer + ")");
        host.onAssistStatus();
    }

    private JSONObject info() throws Exception {
        JSONObject attribution = new JSONObject().put("name", "r2hack")
                .put("url", "https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software");
        JSONObject sat = new JSONObject().put("name", host.robotName()).put("attribution", attribution)
                .put("installed", true).put("description", "R2-D2").put("version", VERSION)
                .put("supports_trigger", false);
        if (mode == ROBOT_WAKE) sat.put("active_wake_words", new org.json.JSONArray().put(WAKE_NAME));
        return new JSONObject().put("asr", new org.json.JSONArray()).put("tts", new org.json.JSONArray())
                .put("handle", new org.json.JSONArray()).put("intent", new org.json.JSONArray())
                .put("wake", new org.json.JSONArray()).put("mic", new org.json.JSONArray())
                .put("snd", new org.json.JSONArray()).put("satellite", sat);
    }

    private static String clip(String s) {
        s = s == null ? "" : s.replace('\n', ' ').trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ---- reply audio ----------------------------------------------------------------------

    private static final class ReplyFormat {
        final int rate, width, channels;

        ReplyFormat(int rate, int width, int channels) {
            this.rate = rate;
            this.width = width;
            this.channels = channels;
        }
    }

    private void startReply(Conn c, JSONObject data) {
        int rate = data.optInt("rate"), width = data.optInt("width"), channels = data.optInt("channels");
        if (rate < 8000 || rate > 48000 || width != 2 || channels < 1 || channels > 2) {
            Log.w(TAG, "reply audio format not supported: " + data);
            return;
        }
        if (speaking) abortReply();
        endStream(c); // an announcement while listening: stop listening
        speaking = true; // the mic stops now (Brain.mayListen)
        speakingTo = c;
        lastReplyAudio = System.currentTimeMillis();
        playQueue.add(new ReplyFormat(rate, width, channels));
        setState(SPEAKING);
    }

    private void abortReply() {
        if (!speaking) return;
        playQueue.clear();
        speaker.abort();
        finishReply(false);
    }

    private synchronized void finishReply(boolean played) {
        if (!speaking) return;
        Conn c = speakingTo;
        speaking = false;
        speakingTo = null;
        if (played && c != null) {
            try {
                c.send("played", null, null);
            } catch (Exception e) {
                c.close();
            }
        }
        if (state == SPEAKING) setState(IDLE);
        if (mode == HA_WAKE && c != null && c == active && !streaming && rerunAt == 0) {
            // keep the continuous stream going (it was only muted while R2 spoke)
            try {
                beginAudio(c);
            } catch (Exception e) {
                c.close();
            }
        }
    }

    private void playerLoop() {
        while (true) {
            try {
                Object item = playQueue.take();
                if (item instanceof ReplyFormat) {
                    ReplyFormat f = (ReplyFormat) item;
                    speaker.open(f.rate, f.width, f.channels);
                } else if (item instanceof byte[]) {
                    speaker.write((byte[]) item);
                } else if (item == END) {
                    speaker.drain();
                    finishReply(true);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                Log.w(TAG, "reply playback failed", e);
                playQueue.clear();
                speaker.abort();
                finishReply(false);
            }
        }
    }

    private void timerLoop() {
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            Conn a = active;
            if (streaming && mode == ROBOT_WAKE && a != null && now - streamStarted > MAX_REQUEST_MS) {
                Log.i(TAG, "request too long, stopping the stream");
                endStream(a);
                if (state == LISTENING) setState(THINKING);
            }
            if (state == THINKING && now - stateSince > MAX_THINKING_MS) setState(IDLE);
            if (state == LISTENING && !streaming && mode == ROBOT_WAKE && now - stateSince > MAX_REQUEST_MS + 1000) {
                setState(IDLE);
            }
            if (mode == HA_WAKE && state == LISTENING && now - stateSince > MAX_THINKING_MS) setState(IDLE);
            if (speaking && now - lastReplyAudio > REPLY_GAP_MS && playQueue.isEmpty()) {
                // audio-stop never came: play what we have and stop
                playQueue.add(END);
                lastReplyAudio = now;
            }
            if (rerunAt != 0 && now > rerunAt) {
                rerunAt = 0;
                if (mode == HA_WAKE && a != null && !streaming) runContinuous(a);
            }
        }
    }

    // ---- server ---------------------------------------------------------------------------

    private void serverLoop() {
        while (true) {
            try {
                if (mode == OFF) {
                    closeServer();
                    Thread.sleep(1000);
                    continue;
                }
                if (server == null) {
                    server = new ServerSocket(port);
                    server.setReuseAddress(true);
                    server.setSoTimeout(1000);
                    advertise(true);
                    Log.i(TAG, "voice satellite listening on :" + port);
                }
                Socket s;
                try {
                    s = server.accept();
                } catch (SocketTimeoutException again) {
                    continue;
                }
                if (!allowed(s.getInetAddress())) {
                    Log.w(TAG, "refused " + s.getInetAddress().getHostAddress());
                    s.close();
                    continue;
                }
                final Conn c = new Conn(s);
                conns.add(c);
                daemon("assist-conn", new Runnable() {
                    public void run() {
                        c.readLoop();
                    }
                });
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "voice satellite server failed, retrying", e);
                closeServer();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void closeServer() {
        if (server == null) return;
        try {
            server.close();
        } catch (Exception ignored) {
        }
        server = null;
        advertise(false);
        for (Conn c : conns) c.close();
    }

    /** Pinned host only, if set; otherwise R2's own subnet (like the agent's API), or loopback. */
    private boolean allowed(InetAddress a) {
        a = unmapped(a);
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;
        if (!pinnedHost.isEmpty()) {
            try {
                return InetAddress.getByName(pinnedHost).equals(a);
            } catch (Exception e) {
                return false;
            }
        }
        if (!(a.isSiteLocalAddress() || a.isLinkLocalAddress())) return false;
        byte[] client = a.getAddress();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress own = ia.getAddress();
                    if (own == null || own.isLoopbackAddress()) continue;
                    byte[] ours = own.getAddress();
                    if (ours.length != client.length) continue;
                    int prefix = ia.getNetworkPrefixLength();
                    if (ours.length == 4 && (prefix <= 0 || prefix > 32)) prefix = 24; // Android 4.x quirk
                    if (ours.length == 16 && (prefix <= 0 || prefix > 128)) prefix = 64;
                    if (samePrefix(client, ours, prefix)) return true;
                }
            }
        } catch (Exception e) {
            return true; // private address already checked; don't lock Home Assistant out on a quirk
        }
        return false;
    }

    private static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        int full = prefix / 8, rest = prefix % 8;
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (b[full] & mask);
    }

    private static InetAddress unmapped(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a;
        byte[] b = a.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
        mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
        if (!mapped) return a;
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (Exception e) {
            return null;
        }
    }

    /** Zeroconf, so Home Assistant offers R2 under Settings > Devices > Discovered. */
    private void advertise(boolean on) {
        if (ctx == null) return;
        try {
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            if (!on) {
                if (nsdListener != null) nsd.unregisterService((NsdManager.RegistrationListener) nsdListener);
                nsdListener = null;
                return;
            }
            if (nsdListener != null) return;
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName(host.robotName().replaceAll("[^A-Za-z0-9-]", "-"));
            si.setServiceType("_wyoming._tcp");
            si.setPort(port);
            NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int err) {
                    Log.w(TAG, "zeroconf registration failed: " + err);
                }

                public void onUnregistrationFailed(NsdServiceInfo s, int err) {
                }

                public void onServiceRegistered(NsdServiceInfo s) {
                    Log.i(TAG, "advertised as " + s.getServiceName());
                }

                public void onServiceUnregistered(NsdServiceInfo s) {
                }
            };
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, l);
            nsdListener = l;
        } catch (Throwable e) {
            Log.w(TAG, "zeroconf unavailable", e);
            nsdListener = null;
        }
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ---- one connection -------------------------------------------------------------------

    private final class Conn {
        final Socket socket;
        final String peer;
        private final OutputStream out;
        private volatile boolean closed;

        Conn(Socket s) throws IOException {
            socket = s;
            peer = s.getInetAddress().getHostAddress();
            s.setSoTimeout(60_000); // Home Assistant pings every few seconds
            s.setTcpNoDelay(true);
            out = s.getOutputStream();
        }

        synchronized void send(String type, JSONObject data, byte[] payload) throws Exception {
            if (closed) throw new IOException("closed");
            JSONObject h = new JSONObject().put("type", type).put("version", VERSION);
            byte[] d = null;
            if (data != null && data.length() > 0) {
                d = data.toString().getBytes(UTF8);
                h.put("data_length", d.length);
            }
            if (payload != null && payload.length > 0) h.put("payload_length", payload.length);
            out.write((h.toString() + "\n").getBytes(UTF8));
            if (d != null) out.write(d);
            if (payload != null && payload.length > 0) out.write(payload);
            out.flush();
        }

        void readLoop() {
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                while (!closed) {
                    String line = readLine(in);
                    if (line == null) break;
                    if (line.trim().isEmpty()) continue;
                    JSONObject h = new JSONObject(line);
                    JSONObject data = h.optJSONObject("data");
                    if (data == null) data = new JSONObject();
                    int dl = h.optInt("data_length", 0), pl = h.optInt("payload_length", 0);
                    if (dl < 0 || dl > MAX_DATA || pl < 0 || pl > MAX_PAYLOAD) throw new IOException("event too large");
                    if (dl > 0) {
                        JSONObject more = new JSONObject(new String(readExactly(in, dl), UTF8));
                        for (Iterator<?> k = more.keys(); k.hasNext(); ) {
                            String key = (String) k.next();
                            data.put(key, more.get(key));
                        }
                    }
                    byte[] payload = pl > 0 ? readExactly(in, pl) : null;
                    onEvent(this, h.getString("type"), data, payload);
                }
            } catch (Exception e) {
                if (!closed) Log.i(TAG, "connection from " + peer + " ended: " + e);
            } finally {
                close();
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            conns.remove(this);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            deactivate(this);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return b.toString("UTF-8");
            if (b.size() >= MAX_HEADER) throw new IOException("header line too long");
            b.write(c);
        }
        return b.size() > 0 ? b.toString("UTF-8") : null;
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("connection closed mid-event");
            off += r;
        }
        return buf;
    }

    // ---- Android speaker ------------------------------------------------------------------

    /** Streams the reply through an AudioTrack on the music stream (same volume and mute as everything else). */
    static final class TrackSpeaker implements Speaker {
        private AudioTrack track;
        private long frames;
        private int rate, frameBytes;

        @Override
        public synchronized void open(int rate, int width, int channels) throws Exception {
            abort();
            int ch = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int min = AudioTrack.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new Exception("audio format not supported");
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, ch, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, rate * width * channels / 2), AudioTrack.MODE_STREAM);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                throw new Exception("speaker not available");
            }
            t.play();
            track = t;
            frames = 0;
            this.rate = rate;
            frameBytes = width * channels;
        }

        @Override
        public void write(byte[] pcm) {
            AudioTrack t;
            synchronized (this) {
                t = track;
            }
            if (t == null) return;
            int n = t.write(pcm, 0, pcm.length - pcm.length % frameBytes); // blocks while the buffer is full
            if (n > 0) frames += n / frameBytes;
        }

        @Override
        public void drain() throws InterruptedException {
            AudioTrack t;
            synchronized (this) {
                t = track;
            }
            if (t == null) return;
            t.stop(); // in stream mode, stop() plays what's buffered first
            long end = System.currentTimeMillis() + frames * 1000 / Math.max(1, rate) + 1500;
            while (System.currentTimeMillis() < end) {
                long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                if (head >= frames) break;
                Thread.sleep(50);
            }
            synchronized (this) {
                if (track == t) {
                    t.release();
                    track = null;
                }
            }
        }

        @Override
        public synchronized void abort() {
            if (track == null) return;
            try {
                track.pause();
                track.flush();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }
}
