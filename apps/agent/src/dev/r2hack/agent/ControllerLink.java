package dev.r2hack.agent;

/** The robot's controller board: the real UART (SerialLink) or the simulator (SimController). */
interface ControllerLink {
    interface Listener {
        void onControllerLine(String line);
    }

    void start();

    boolean isOpen();

    long lastRxAt();

    String lastGin();

    /** Sends one JSON object as a line. */
    boolean send(String json);
}
