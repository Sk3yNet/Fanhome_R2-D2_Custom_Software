package dev.r2hack.agent;

import org.json.JSONObject;

/**
 * A pretend controller for working without a powered robot (e.g. on a spare main board): it answers
 * gin with a status that follows the commands it receives, logs every command, and can inject button
 * presses. Turned on with POST /sim?on=1 and a restart; /dev/ttyS2 is left alone while it runs.
 */
final class SimController implements ControllerLink {
    private final Listener listener;
    private volatile long lastRxAt;
    private volatile String lastGin;
    private int arm, lightsaber, projector, lcdS = 1, lcdL = 1, mode = 3;
    private volatile int battery = 80, charging;

    SimController(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        EventLog.i("SIMULATED controller: commands are logged, nothing reaches /dev/ttyS2");
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public long lastRxAt() {
        return lastRxAt;
    }

    @Override
    public String lastGin() {
        return lastGin;
    }

    @Override
    public synchronized boolean send(String json) {
        JSONObject c;
        try {
            c = new JSONObject(json.trim());
        } catch (Exception e) {
            EventLog.i("sim: ignoring non-JSON " + json.trim());
            return true;
        }
        String cmd = c.optString("cmd");
        if (!"gin".equals(cmd)) EventLog.i("sim <- " + json.trim());
        switch (cmd) {
            case "gin": reply(gin()); break;
            case "ready": reply("{\"cmd\":\"ready\"}"); break;
            case "arm": arm = c.optInt("power"); break;
            case "lightsaber": lightsaber = c.optInt("power"); break;
            case "projector": projector = c.optInt("mode"); break;
            case "lcd":
                lcdS = c.optInt("s", lcdS);
                lcdL = c.optInt("l", lcdL);
                break;
            default: break;
        }
        return true;
    }

    /** Pretends a robot button was pressed (see the btn values in docs/serial-protocol.md). */
    void pressButton(int value) {
        reply("{\"cmd\":\"btn\",\"value\":" + value + "}");
    }

    void setBattery(int percent, int chargingStatus) {
        battery = percent;
        charging = chargingStatus;
    }

    private String gin() {
        return "{\"cmd\":\"gin\",\"batt\":" + battery + ",\"charging-status\":" + charging + ",\"charging_status\":" + charging
                + ",\"arm\":" + arm + ",\"lightsaber\":" + lightsaber + ",\"projector\":" + projector
                + ",\"lcd_s\":" + lcdS + ",\"lcd_l\":" + lcdL + ",\"mode\":" + mode
                + ",\"status\":0,\"error\":\"NO ERROR\",\"sim\":true}";
    }

    private void reply(String line) {
        lastRxAt = System.currentTimeMillis();
        if (line.contains("\"gin\"")) lastGin = line;
        listener.onControllerLine(line);
    }
}
