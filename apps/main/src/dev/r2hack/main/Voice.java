package dev.r2hack.main;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import edu.cmu.pocketsphinx.PocketSphinxJNI;
import edu.cmu.pocketsphinx.SphinxBaseJNI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline voice commands, like the factory software: keyword spotting with CMU PocketSphinx. The
 * recognizer library and its speech model come from the factory app on the robot (nothing is bundled);
 * the command phrases are our own and can be changed in files/voice/keywords.txt.
 *
 * "Hey R2-D2" wakes it up; for 15 s after that, each command phrase runs its action,
 * and each command keeps it listening for another 15 s. With Home Assistant's voice pipeline (Assist)
 * connected, what follows "hey R2-D2" goes there instead, or all audio does if Home Assistant spots the
 * wake word itself.
 */
final class Voice {
    interface Host {
        void onVoiceCommand(String action);

        /** A routine's own voice phrase (a "voice" trigger, docs/routines.md) was heard after the wake phrase. */
        void onVoiceRoutine(String routine);

        /** Listening on/off, for the back light. */
        void onVoiceAwake(boolean awake);

        /** False while R2 shouldn't listen (sleep, patrol, pairing, projector audio, music...). */
        boolean mayListen();
    }

    /** Somewhere else the microphone audio can go instead (Home Assistant's voice pipeline: Assist). */
    interface Stream {
        /** The wake phrase was heard. True if the stream takes the request instead of the local commands. */
        boolean takeWake();

        /** True while audio should go to audio() instead of the keyword spotter. */
        boolean wantsAudio();

        void audio(short[] buf, int n);
    }

    private static final String TAG = "R2Voice";
    private static final String STOCK_PACKAGE = "com.bullb.r2d2_nanopisystem";
    private static final String LANG = "en";
    private static final int RATE = 16000;
    private static final long AWAKE_MS = 15_000;
    private static final String WAKE = "wake";
    /** Spotting threshold for routines' phrases: between the built-in commands' values. */
    private static final String ROUTINE_THRESHOLD = "1e-20";

    /** Phrase -> action, with the keyword-spotting threshold (lower finds more, but also more false alarms). */
    private static final Object[][] PHRASES = {
            // Only "hey R2-D2" wakes him: plain "R2-D2" comes up whenever people talk about him.
            {"hey r two d two", WAKE, "1e-34"},
            // Commands that move him are stricter, so conversation near him doesn't drive him around.
            {"turn left", "turn-left", "1e-14"}, {"turn right", "turn-right", "1e-12"},
            {"turn around", "turn-around", "1e-14"}, {"go forward", "forward", "1e-18"},
            {"shake your head", "shake-head", "1e-22"}, {"walk a circle", "circle", "1e-26"},
            {"dance please", "dance", "1e-18"}, {"make some noise", "noise", "1e-22"},
            {"who are you", "who-are-you", "1e-16"}, {"lightsaber", "lightsaber", "1e-22"},
            {"move arms", "arm", "1e-16"}, {"patrol", "patrol", "1e-8"}, {"stop", "stop", "1e-10"},
            {"princess leia", "leia", "1e-20"}, {"skywalker", "luke", "1e-20"},
    };

    private final Context ctx;
    private final Host host;
    private final Map<String, String> actions = new LinkedHashMap<>();
    private volatile boolean enabled = true;
    private volatile String problem = "starting";
    private volatile long awakeUntil;
    private volatile Stream stream;
    private volatile Map<String, String> routinePhrases = new LinkedHashMap<>(); // phrase -> routine, as spotted now
    private volatile Map<String, String> wantedPhrases = new LinkedHashMap<>();  // as the routines want them
    private volatile boolean reload;
    private volatile String phraseProblem = "";
    private File modelDir;
    private int searchGen;

    Voice(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        for (Object[] p : PHRASES) actions.put((String) p[0], (String) p[1]);
    }

    void setStream(Stream s) {
        stream = s;
    }

    void setEnabled(boolean on) {
        enabled = on;
    }

    boolean awake() {
        return System.currentTimeMillis() < awakeUntil;
    }

    String status() {
        int n = routinePhrases.size();
        return "voice: " + (problem != null ? problem : !enabled ? "off" : awake() ? "listening for a command" : "waiting for \"hey R2-D2\"")
                + (n > 0 ? ", " + n + " routine phrase" + (n == 1 ? "" : "s") : "") + phraseProblem;
    }

    /** The routines' voice phrases (phrase -> routine name). The recognizer picks them up between utterances. */
    void setRoutinePhrases(Map<String, String> phrases) {
        if (phrases.equals(wantedPhrases)) return;
        wantedPhrases = new LinkedHashMap<>(phrases);
        reload = true;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop();
                } catch (Throwable e) {
                    problem = "unavailable (" + e.getMessage() + ")";
                    Log.w(TAG, "voice commands unavailable", e);
                }
            }
        }, "voice");
        t.setDaemon(true);
        t.start();
    }

    private void loop() throws Exception {
        File dir = prepareModel();
        modelDir = dir;
        long config = PocketSphinxJNI.Decoder_defaultConfig();
        SphinxBaseJNI.Config_setString(config, null, "-hmm", new File(dir, "ptm").getPath());
        SphinxBaseJNI.Config_setString(config, null, "-dict", new File(dir, "cmudict.dict").getPath());
        SphinxBaseJNI.Config_setFloat(config, null, "-samprate", RATE);
        SphinxBaseJNI.Config_setString(config, null, "-logfn", "/dev/null");
        long decoder = PocketSphinxJNI.new_Decoder__SWIG_1(config, null);
        if (decoder == 0) throw new Exception("recognizer would not start");
        reload = false;
        PocketSphinxJNI.Decoder_setKws(decoder, null, "command0", keywordsFile().getPath());
        PocketSphinxJNI.Decoder_setSearch(decoder, null, "command0");

        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        short[] buf = new short[RATE * 4 / 10]; // 0.4 s, like the factory recognizer
        problem = null;
        Log.i(TAG, "voice commands ready");
        while (true) {
            if (!enabled || !host.mayListen()) {
                if (awakeUntil != 0) sleepVoice();
                Thread.sleep(500);
                continue;
            }
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, buf.length * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                problem = "microphone not available";
                Thread.sleep(5000);
                continue;
            }
            problem = null;
            rec.startRecording();
            PocketSphinxJNI.Decoder_startUtt(decoder, null);
            boolean streamed = false;
            try {
                while (enabled && host.mayListen()) {
                    if (reload) { // routines' phrases changed: switch to a new keyword search between utterances
                        PocketSphinxJNI.Decoder_endUtt(decoder, null);
                        switchSearch(decoder);
                        PocketSphinxJNI.Decoder_startUtt(decoder, null);
                    }
                    int n = rec.read(buf, 0, buf.length);
                    if (n < 0) throw new Exception("microphone read failed (" + n + ")");
                    if (n == 0) continue;
                    Stream st = stream;
                    if (st != null && st.wantsAudio()) {
                        st.audio(buf, n);
                        streamed = true;
                        continue;
                    }
                    if (streamed) { // back from streaming: don't spot words in a stale utterance
                        streamed = false;
                        PocketSphinxJNI.Decoder_endUtt(decoder, null);
                        PocketSphinxJNI.Decoder_startUtt(decoder, null);
                    }
                    PocketSphinxJNI.Decoder_processRaw(decoder, null, buf, n, false, false);
                    String heard = hypothesis(decoder);
                    if (heard != null) {
                        // Start a fresh utterance so the same words don't trigger again.
                        PocketSphinxJNI.Decoder_endUtt(decoder, null);
                        PocketSphinxJNI.Decoder_startUtt(decoder, null);
                        onHeard(heard.trim().toLowerCase(Locale.US));
                    }
                    if (awakeUntil != 0 && System.currentTimeMillis() > awakeUntil) sleepVoice();
                }
            } catch (Exception e) {
                Log.w(TAG, "listening stopped", e);
                Thread.sleep(2000);
            } finally {
                PocketSphinxJNI.Decoder_endUtt(decoder, null);
                rec.stop();
                rec.release();
            }
        }
    }

    /**
     * Loads the keyword list again under a new search name and switches to it. The two names take
     * turns, so the search being replaced is never the one in use.
     */
    private void switchSearch(long decoder) throws Exception {
        reload = false;
        String name = "command" + (++searchGen % 2);
        PocketSphinxJNI.Decoder_setKws(decoder, null, name, keywordsFile().getPath());
        PocketSphinxJNI.Decoder_setSearch(decoder, null, name);
        Log.i(TAG, "voice phrases reloaded: " + routinePhrases.keySet());
    }

    private static String hypothesis(long decoder) {
        long hyp = PocketSphinxJNI.Decoder_hyp(decoder, null);
        if (hyp == 0) return null;
        try {
            String s = PocketSphinxJNI.Hypothesis_hypstr_get(hyp, null);
            return s == null || s.trim().isEmpty() ? null : s;
        } finally {
            PocketSphinxJNI.delete_Hypothesis(hyp);
        }
    }

    private void onHeard(String text) {
        String action = null;
        for (Map.Entry<String, String> e : actions.entrySet()) {
            if (text.startsWith(e.getKey())) {
                action = e.getValue();
                break;
            }
        }
        String routine = null;
        if (action == null) {
            for (Map.Entry<String, String> e : routinePhrases.entrySet()) {
                if (text.startsWith(e.getKey())) {
                    routine = e.getValue();
                    break;
                }
            }
        }
        Log.i(TAG, "heard \"" + text + "\" -> " + (routine != null ? "routine " + routine : action));
        if (routine != null) {
            if (awake()) {
                awakeUntil = System.currentTimeMillis() + AWAKE_MS;
                host.onVoiceRoutine(routine);
            }
            return;
        }
        if (action == null) return;
        if (WAKE.equals(action)) {
            Stream st = stream;
            if (st != null && st.takeWake()) { // Home Assistant answers this one
                if (awakeUntil != 0) sleepVoice();
                return;
            }
            boolean was = awake();
            awakeUntil = System.currentTimeMillis() + AWAKE_MS;
            if (!was) host.onVoiceAwake(true);
            host.onVoiceCommand("wake");
        } else if (awake()) {           // commands only count after the wake phrase
            awakeUntil = System.currentTimeMillis() + AWAKE_MS;
            host.onVoiceCommand(action);
        }
    }

    private void sleepVoice() {
        awakeUntil = 0;
        host.onVoiceAwake(false);
    }

    /**
     * Our phrase list as a PocketSphinx keyword file (files/voice/keywords.txt replaces the built-in
     * part), plus the routines' phrases whose words are all in the speech model's dictionary.
     */
    private File keywordsFile() throws Exception {
        File custom = new File(ctx.getFilesDir(), "voice/keywords.txt");
        StringBuilder sb = new StringBuilder();
        if (custom.exists()) {
            Log.i(TAG, "using " + custom);
            sb.append(new String(readAll(custom), "UTF-8").trim()).append('\n');
        } else {
            for (Object[] p : PHRASES) sb.append(p[0]).append(" /").append(p[2]).append("/\n");
        }
        Map<String, String> wanted = wantedPhrases, usable = new LinkedHashMap<>();
        java.util.Set<String> known = knownWords(wanted.keySet());
        StringBuilder skipped = new StringBuilder();
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String missing = null;
            for (String w : e.getKey().split(" ")) if (!known.contains(w)) missing = w;
            if (missing != null) {
                skipped.append(skipped.length() > 0 ? "; " : "").append("\"").append(e.getKey()).append("\" (")
                        .append(e.getValue()).append("): \"").append(missing).append("\" isn't in the dictionary");
                continue;
            }
            usable.put(e.getKey(), e.getValue());
            sb.append(e.getKey()).append(" /").append(ROUTINE_THRESHOLD).append("/\n");
        }
        phraseProblem = skipped.length() > 0 ? ", skipped " + skipped : "";
        if (skipped.length() > 0) Log.w(TAG, "routine phrases" + phraseProblem);
        routinePhrases = usable;
        File f = new File(ctx.getFilesDir(), "voice/r2-keywords");
        OutputStream out = new FileOutputStream(f);
        out.write(sb.toString().getBytes("UTF-8"));
        out.close();
        return f;
    }

    /** The words of these phrases that the speech model's dictionary knows (keyword search fails on any other). */
    private java.util.Set<String> knownWords(java.util.Collection<String> phrases) throws Exception {
        java.util.Set<String> want = new java.util.HashSet<>(), found = new java.util.HashSet<>();
        for (String p : phrases) want.addAll(java.util.Arrays.asList(p.split(" ")));
        if (want.isEmpty() || modelDir == null) return found;
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(new File(modelDir, "cmudict.dict")), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String w = line.substring(0, sp);
                if (want.contains(w)) found.add(w);
            }
        } finally {
            r.close();
        }
        return found;
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Loads the recognizer library and copies the speech model out of the factory app (once).
     * Returns the model folder.
     */
    private File prepareModel() throws Exception {
        ApplicationInfo ai;
        try {
            ai = ctx.getPackageManager().getApplicationInfo(STOCK_PACKAGE, 0);
        } catch (Exception e) {
            throw new Exception("factory app not found");
        }
        File base = new File(ctx.getFilesDir(), "voice");
        File dir = new File(base, LANG);
        File lib = new File(base, "libpocketsphinx_jni.so");
        File done = new File(dir, ".copied");
        ZipFile apk = new ZipFile(ai.sourceDir);
        try {
            if (!lib.exists()) copy(apk, "lib/armeabi-v7a/libpocketsphinx_jni.so", lib);
            if (!done.exists()) {
                String prefix = "assets/sync/" + LANG + "/";
                Enumeration<? extends ZipEntry> all = apk.entries();
                int n = 0;
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.startsWith(prefix) || name.endsWith(".md5")) continue;
                    copy(apk, name, new File(dir, name.substring(prefix.length())));
                    n++;
                }
                if (n == 0) throw new Exception("no speech model in the factory app");
                done.createNewFile();
            }
        } finally {
            apk.close();
        }
        System.load(lib.getPath());
        return dir;
    }

    private static void copy(ZipFile zip, String entry, File to) throws Exception {
        ZipEntry e = zip.getEntry(entry);
        if (e == null) throw new Exception(entry + " missing");
        to.getParentFile().mkdirs();
        File tmp = new File(to.getPath() + ".tmp");
        InputStream in = zip.getInputStream(e);
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        if (!tmp.renameTo(to)) throw new Exception("could not save " + to);
    }
}
