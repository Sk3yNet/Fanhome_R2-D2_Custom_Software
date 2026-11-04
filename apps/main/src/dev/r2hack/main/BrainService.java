package dev.r2hack.main;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import org.json.JSONObject;

/**
 * Connects to the agent bridge, sends heartbeats so the agent knows we're alive,
 * and hands every controller message to Brain. If this process hangs or crashes,
 * the heartbeats stop and the agent stops the robot and restarts (or rolls back) the app.
 */
public class BrainService extends Service {
    private static final String TAG = "R2Main";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String AGENT_WAKE = "dev.r2hack.agent.WAKE";
    private static final long AGENT_WAKE_AFTER_MS = 30000;
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static volatile String connState = "connecting";
    private static volatile String lastGin = "-";

    private static BrainService instance;
    private volatile OutputStream out;
    private Brain brain;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        brain = new Brain(this);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "bridge");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Sends a command to the robot controller, e.g. {"cmd":"led","r":1}. */
    synchronized boolean send(JSONObject cmd) {
        return writeLine(cmd.toString());
    }

    /** Asks the agent itself to do something, e.g. "wifi-toggle". */
    boolean toAgent(String kind) {
        return writeLine("{\"agent\":\"" + kind + "\"}");
    }

    private synchronized boolean writeLine(String line) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            o.write((line + "\n").getBytes(UTF8));
            o.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Watches the watcher: if the agent's bridge stays down, ask the system to start the agent. */
    private void wakeAgent() {
        Log.w(TAG, "agent unreachable, sending wake");
        Intent i = new Intent(AGENT_WAKE).setClassName("dev.r2hack.agent", "dev.r2hack.agent.BootReceiver");
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(i);
    }

    private void connectLoop() {
        long lastOk = System.currentTimeMillis(), lastWake = 0;
        while (true) {
            Socket s = null;
            long now = System.currentTimeMillis();
            if (now - lastOk > AGENT_WAKE_AFTER_MS && now - lastWake > AGENT_WAKE_AFTER_MS) {
                lastWake = now;
                wakeAgent();
            }
            try {
                s = new Socket("127.0.0.1", 7701);
                lastOk = System.currentTimeMillis();
                s.setTcpNoDelay(true);
                out = s.getOutputStream();
                connState = "connected to agent";
                writeLine("{\"agent\":\"hello\",\"version\":\"" + BuildInfo.VERSION + "\"}");
                startHeartbeat(s);
                brain.onConnected();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
                String line;
                while ((line = r.readLine()) != null) {
                    remember(line);
                    try {
                        JSONObject msg = new JSONObject(line);
                        if (msg.has("agent")) {
                            brain.onAgentMessage(msg);
                            continue;
                        }
                        if ("gin".equals(msg.optString("cmd"))) lastGin = line;
                        brain.onControllerMessage(msg);
                    } catch (Exception e) {
                        Log.w(TAG, "bad line: " + line, e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "bridge connection failed", e);
            }
            out = null;
            if (s != null) lastOk = System.currentTimeMillis(); // was connected until just now
            connState = "agent not reachable, retrying";
            try {
                if (s != null) s.close();
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }
        }
    }

    private void startHeartbeat(final Socket s) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed() && writeLine("{\"agent\":\"heartbeat\"}")) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void remember(String line) {
        synchronized (recent) {
            if (recent.size() >= 20) recent.removeFirst();
            recent.addLast(line);
        }
    }

    static String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("R2 Main ").append(BuildInfo.VERSION).append('\n');
        sb.append(connState).append("\n");
        BrainService me = instance;
        if (me != null) sb.append(me.brain.status()).append('\n');
        sb.append('\n');
        sb.append("last status: ").append(lastGin).append("\n\n");
        sb.append("recent controller messages:\n");
        synchronized (recent) {
            for (String l : recent) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
