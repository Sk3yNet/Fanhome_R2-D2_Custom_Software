package dev.r2hack.agent;

import android.content.Context;
import com.bullb.r2d2_nanopisystem.SerialPort.SerialPortOrangePi;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Owns /dev/ttyS2: newline-delimited JSON to and from the robot's controller board. */
final class SerialLink implements ControllerLink {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final Listener listener;
    private final SerialPortOrangePi port = new SerialPortOrangePi();
    private volatile OutputStream out;
    private volatile long lastRxAt;
    private volatile String lastGin;

    SerialLink(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    @Override
    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "serial-rx");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean isOpen() {
        return out != null;
    }

    @Override
    public long lastRxAt() {
        return lastRxAt;
    }

    @Override
    public String lastGin() {
        return lastGin;
    }

    /** Sends one JSON object; a trailing newline is added like the stock app does. */
    @Override
    public synchronized boolean send(String json) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            String line = json.endsWith("\n") ? json : json + "\n";
            o.write(line.getBytes(UTF8));
            o.flush();
            return true;
        } catch (Exception e) {
            EventLog.e("serial write failed", e);
            return false;
        }
    }

    private void readLoop() {
        while (true) {
            try {
                NativeLibs.loadR2d2(ctx);
                FileDescriptor fd = port.openPort(Config.SERIAL_DEVICE, Config.SERIAL_BAUD);
                if (fd == null) throw new Exception("native open returned null");
                InputStream in = new FileInputStream(fd);
                out = new FileOutputStream(fd);
                EventLog.i("serial open " + Config.SERIAL_DEVICE + " @" + Config.SERIAL_BAUD);
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                    if (b == '\n') {
                        String s = new String(line.toByteArray(), UTF8).trim();
                        line.reset();
                        if (s.length() > 2 && s.charAt(0) == '{') {
                            lastRxAt = System.currentTimeMillis();
                            if (s.contains("\"gin\"")) lastGin = s;
                            listener.onControllerLine(s);
                        }
                    } else if (line.size() < 4096) {
                        line.write(b);
                    }
                }
                throw new Exception("serial EOF");
            } catch (Throwable e) {
                EventLog.e("serial link down, retrying in 2s", e);
                out = null;
                try {
                    port.closePort();
                } catch (Throwable ignored) {
                }
                sleep(2000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
