package dev.r2hack.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/**
 * Localhost TCP bridge for the main app. Every controller line is copied to all
 * clients; lines from clients go to the controller, except messages carrying an
 * "agent" key, which are heartbeats/control for the agent itself.
 */
final class Bridge {
    interface Handler {
        void onAgentMessage(JSONObject msg);

        boolean sendToController(String line);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final Handler handler;
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<>();

    Bridge(Handler handler) {
        this.handler = handler;
    }

    int clientCount() {
        return clients.size();
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "bridge-accept");
        t.setDaemon(true);
        t.start();
    }

    void broadcast(String line) {
        byte[] data = (line + "\n").getBytes(UTF8);
        for (Socket s : clients) {
            try {
                OutputStream o = s.getOutputStream();
                synchronized (s) {
                    o.write(data);
                    o.flush();
                }
            } catch (Exception e) {
                drop(s);
            }
        }
    }

    private void acceptLoop() {
        while (true) {
            try {
                ServerSocket server = new ServerSocket(Config.BRIDGE_PORT, 4, InetAddress.getByName("127.0.0.1"));
                server.setReuseAddress(true);
                EventLog.i("bridge listening on 127.0.0.1:" + Config.BRIDGE_PORT);
                while (true) {
                    final Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    clients.add(s);
                    EventLog.i("bridge client connected");
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            clientLoop(s);
                        }
                    }, "bridge-client");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Exception e) {
                EventLog.e("bridge server failed, retrying", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void clientLoop(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject obj;
                try {
                    obj = new JSONObject(line);
                } catch (Exception e) {
                    EventLog.i("bridge: ignoring non-JSON line");
                    continue;
                }
                if (obj.has("agent")) {
                    handler.onAgentMessage(obj);
                } else {
                    handler.sendToController(line);
                }
            }
        } catch (Exception ignored) {
        } finally {
            drop(s);
        }
    }

    private void drop(Socket s) {
        if (clients.remove(s)) EventLog.i("bridge client disconnected");
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
