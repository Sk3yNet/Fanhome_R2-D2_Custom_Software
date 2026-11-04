package dev.r2hack.main;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

/**
 * SSDP: announces the renderer on the local network and answers searches, so Home Assistant
 * (and other DLNA controllers) find it without configuration.
 */
final class Ssdp {
    private static final String TAG = "R2Ssdp";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final int MAX_AGE = 1800;

    private final Context ctx;
    private final String udn;
    private final int httpPort;
    private final String[] types;
    private final Random random = new Random();
    private MulticastSocket socket;

    Ssdp(Context ctx, String udn, int httpPort) {
        this.ctx = ctx;
        this.udn = udn;
        this.httpPort = httpPort;
        this.types = new String[] {"upnp:rootdevice", "uuid:" + udn, DlnaXml.DEVICE_TYPE, DlnaXml.AVT, DlnaXml.RC, DlnaXml.CM};
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ssdp");
        t.setDaemon(true);
        t.start();
    }

    /** The robot's own IPv4 address on Wi-Fi (station or hotspot). */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void loop() {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("r2-ssdp"); // Wi-Fi drops multicast without it
        lock.setReferenceCounted(false);
        while (true) {
            try {
                lock.acquire();
                socket = new MulticastSocket(PORT);
                socket.joinGroup(InetAddress.getByName(GROUP));
                socket.setSoTimeout(1000);
                long nextNotify = 0;
                // Reopened every 10 minutes, so a Wi-Fi switch (station <-> hotspot) can't leave it deaf.
                long reopenAt = System.currentTimeMillis() + 600_000;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < reopenAt) {
                    if (System.currentTimeMillis() >= nextNotify) {
                        notifyAlive();
                        nextNotify = System.currentTimeMillis() + 60_000;
                    }
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (java.net.SocketTimeoutException idle) {
                        continue;
                    }
                    onPacket(new String(p.getData(), 0, p.getLength(), UTF8), p.getAddress(), p.getPort());
                }
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "ssdp failed, retrying", e);
                if (socket != null) socket.close();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void onPacket(String msg, InetAddress from, int port) throws Exception {
        if (!msg.startsWith("M-SEARCH")) return;
        String st = header(msg, "ST");
        if (st == null) return;
        String mx = header(msg, "MX");
        int delayMs = 0;
        try {
            delayMs = random.nextInt(Math.max(1, Math.min(3, Integer.parseInt(mx.trim()))) * 1000);
        } catch (Exception ignored) {
        }
        for (String t : types) {
            if (st.equals("ssdp:all") || st.equals(t)) {
                if (delayMs > 0) Thread.sleep(Math.min(delayMs, 500));
                send("HTTP/1.1 200 OK\r\n" + common() + "ST: " + t + "\r\nUSN: " + usn(t) + "\r\n\r\n", from, port);
            }
        }
    }

    private void notifyAlive() throws Exception {
        InetAddress group = InetAddress.getByName(GROUP);
        for (String t : types) {
            send("NOTIFY * HTTP/1.1\r\nHOST: " + GROUP + ":" + PORT + "\r\n" + common() + "NT: " + t
                    + "\r\nNTS: ssdp:alive\r\nUSN: " + usn(t) + "\r\n\r\n", group, PORT);
        }
    }

    private String common() {
        return "CACHE-CONTROL: max-age=" + MAX_AGE + "\r\nEXT:\r\nLOCATION: http://" + localIp() + ":" + httpPort
                + "/dlna/description.xml\r\nSERVER: Android/4.4 UPnP/1.0 R2Main/" + BuildInfo.VERSION + "\r\n";
    }

    private String usn(String type) {
        return type.startsWith("uuid:") ? type : "uuid:" + udn + "::" + type;
    }

    private void send(String text, InetAddress to, int port) throws Exception {
        if (localIp() == null) return;
        byte[] b = text.getBytes(UTF8);
        socket.send(new DatagramPacket(b, b.length, to, port));
    }

    private static String header(String msg, String name) {
        for (String line : msg.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().toUpperCase(Locale.US).equals(name)) return line.substring(c + 1).trim();
        }
        return null;
    }
}
