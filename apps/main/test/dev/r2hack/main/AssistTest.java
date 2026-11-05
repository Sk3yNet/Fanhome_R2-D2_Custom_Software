package dev.r2hack.main;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Off-robot tests for Assist's replies: in the pipeline's voice, in beeps with subtitles, and
 * announcements (which stay spoken in beeps mode). A fake Home Assistant talks Wyoming to a real
 * Assist on a local port; the speaker and beeper are recorders.
 */
public final class AssistTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static int failures;

    public static void main(String[] args) throws Exception {
        beepPlans();
        replies();
        if (failures > 0) {
            System.out.println(failures + " assist test(s) FAILED");
            System.exit(1);
        }
        System.out.println("assist tests passed");
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            System.out.println("FAIL " + what);
            failures++;
        }
    }

    // ---- Beeps.plan ------------------------------------------------------------------------------

    private static void beepPlans() {
        int[] pool = {5, 1, 7, 8};
        Random rnd = new Random(1);
        check(Beeps.plan("", pool, rnd).length == 1, "an empty reply still gets one sound");
        check(Beeps.plan("Yes.", pool, rnd).length == 1, "a one-word reply gets one sound");
        check(Beeps.plan("It is half past three in the afternoon.", pool, rnd).length == 2, "eight words get two sounds");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append("word ");
        check(Beeps.plan(longText.toString(), pool, rnd).length == Beeps.MAX_SOUNDS, "a long reply is capped");
        boolean repeats = false, inPool = true;
        for (int k = 0; k < 200; k++) {
            int[] p = Beeps.plan(longText.toString(), pool, rnd);
            for (int i = 0; i < p.length; i++) {
                if (i > 0 && p[i] == p[i - 1]) repeats = true;
                boolean found = false;
                for (int x : pool) found |= x == p[i];
                inPool &= found;
            }
        }
        check(!repeats, "no sound twice in a row");
        check(inPool, "only sounds from the pool");
        check(Beeps.plan("a b c d e f g", new int[] {3}, rnd)[1] == 3, "a one-sound pool works");
    }

    // ---- replies over Wyoming ----------------------------------------------------------------------

    static final class Recorder implements Assist.Host, Assist.Speaker, Assist.Beeper {
        final List<String> said = Collections.synchronizedList(new ArrayList<String>());
        final List<String> replies = Collections.synchronizedList(new ArrayList<String>());
        volatile int bytesPlayed;

        public void onAssistWake(boolean sound) {}
        public void onAssistState(int state) {}
        public void onAssistStatus() {}
        public void onAssistError(String text) {}
        public boolean playingSound() { return false; }
        public String robotName() { return "R2-D2"; }
        public void onAssistReply(String text, boolean beeps) { replies.add((beeps ? "beeps:" : "voice:") + text); }

        public void open(int rate, int width, int channels) {}
        public void write(byte[] pcm) { bytesPlayed += pcm.length; }
        public void drain() {}
        public void abort() {}

        public void say(String text) throws InterruptedException {
            said.add(text);
            Thread.sleep(200); // beeping takes a moment; "played" must wait for it
        }
        public void stop() {}
    }

    /** The Home Assistant end of the connection. */
    static final class FakeHa {
        final Socket s;
        final OutputStream out;
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

        FakeHa(int port) throws Exception {
            s = new Socket("127.0.0.1", port);
            out = s.getOutputStream();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    readLoop();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void send(String type, JSONObject data, byte[] payload) throws Exception {
            JSONObject h = new JSONObject().put("type", type);
            byte[] d = data == null ? new byte[0] : data.toString().getBytes(UTF8);
            if (d.length > 0) h.put("data_length", d.length);
            if (payload != null) h.put("payload_length", payload.length);
            out.write((h + "\n").getBytes(UTF8));
            out.write(d);
            if (payload != null) out.write(payload);
            out.flush();
        }

        void readLoop() {
            try {
                InputStream in = new BufferedInputStream(s.getInputStream());
                while (true) {
                    ByteArrayOutputStream line = new ByteArrayOutputStream();
                    int c;
                    while ((c = in.read()) >= 0 && c != '\n') line.write(c);
                    if (c < 0) return;
                    JSONObject h = new JSONObject(line.toString("UTF-8"));
                    long skip = h.optInt("data_length") + h.optInt("payload_length");
                    while (skip > 0) skip -= in.skip(skip);
                    events.add(h.getString("type"));
                }
            } catch (Exception ignored) {
            }
        }

        /** Waits for an event of this type, dropping the others (audio chunks...). */
        boolean await(String type, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                String e = events.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (type.equals(e)) return true;
            }
            return false;
        }

        /** The reply audio: start, one chunk of 0.1 s silence, stop. */
        void replyAudio() throws Exception {
            JSONObject fmt = new JSONObject().put("rate", 22050).put("width", 2).put("channels", 1);
            send("audio-start", fmt, null);
            send("audio-chunk", fmt, new byte[4410]);
            send("audio-stop", null, null);
        }

        /** A request in robot mode: R2 heard "hey R2-D2", HA understood it and answers. */
        void request(Assist a, String reply) throws Exception {
            check(a.takeWake(), "R2 starts a request after his wake word");
            check(await("run-pipeline", 2000), "R2 asks Home Assistant to run the pipeline");
            send("transcript", new JSONObject().put("text", "what time is it"), null);
            send("synthesize", new JSONObject().put("text", reply), null);
            replyAudio();
        }
    }

    private static int freePort() throws Exception {
        ServerSocket s = new ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    private static void replies() throws Exception {
        Recorder r = new Recorder();
        int port = freePort();
        Assist a = new Assist(null, r, r, r, port);
        a.configure("robot", null);
        a.start();
        FakeHa ha = null;
        for (int i = 0; i < 50 && ha == null; i++) {
            try {
                ha = new FakeHa(port);
            } catch (Exception notYet) {
                Thread.sleep(100);
            }
        }
        check(ha != null, "the satellite listens");
        if (ha == null) return;
        ha.send("run-satellite", null, null);
        for (int i = 0; i < 50 && !a.connected(); i++) Thread.sleep(50);
        check(a.connected(), "Home Assistant is the active connection");

        // 1. The default: the pipeline's voice, with the text as a subtitle.
        check(!a.beepReplies(), "voice replies by default");
        ha.request(a, "It is three o'clock.");
        check(ha.await("played", 3000), "voice reply: R2 reports it played");
        check(r.bytesPlayed == 4410, "voice reply: the audio went to the speaker");
        check(r.said.isEmpty(), "voice reply: no beeps");
        check(r.replies.contains("voice:It is three o'clock."), "voice reply: the subtitle is reported");

        // 2. Beeps: the audio is dropped, the beeper gets the text, "played" comes after the beeps.
        a.setBeepReplies(true);
        check(a.status().contains("answers in beeps"), "status shows beep replies");
        r.bytesPlayed = 0;
        long t0 = System.currentTimeMillis();
        ha.request(a, "It is  three\no'clock.");
        check(ha.await("played", 3000), "beep reply: R2 reports it played");
        check(System.currentTimeMillis() - t0 >= 200, "beep reply: played only after the beeps");
        check(r.bytesPlayed == 0, "beep reply: the spoken audio is not played");
        check(r.said.size() == 1 && "It is three o'clock.".equals(r.said.get(0)), "beep reply: beeps for the reply's text");
        check(r.replies.contains("beeps:It is three o'clock."), "beep reply: the translation is reported as a subtitle");
        check(!a.speaking(), "beep reply: done speaking");

        // 3. An announcement (no request before it) still plays as sent, even in beeps mode.
        r.said.clear();
        ha.send("synthesize", new JSONObject().put("text", "Dinner is ready."), null);
        ha.replyAudio();
        check(ha.await("played", 3000), "announcement: R2 reports it played");
        check(r.bytesPlayed == 4410, "announcement: spoken, not beeped");
        check(r.said.isEmpty(), "announcement: no beeps");

        // 4. Stop while beeping ends the reply without "played".
        a.setBeepReplies(true);
        ha.request(a, "A long answer that R2 is still beeping when you stop him.");
        Thread.sleep(50);
        a.stop();
        check(!a.speaking(), "stop: no longer speaking");
        check(!ha.await("played", 600), "stop: no played after a stopped reply");
    }
}
