package dev.r2hack.agent;

/** Fixed settings. The token lives in the generated Secrets class. */
final class Config {
    static final String SERIAL_DEVICE = "/dev/ttyS2";
    static final int SERIAL_BAUD = 115200;

    static final int HTTP_PORT = 7700;        // update / control API (all interfaces, token required)
    static final int BRIDGE_PORT = 7701;      // localhost only: main app <-> controller JSON lines
    static final int ANNOUNCE_PORT = 7702;    // UDP broadcast so the PC can find the robot

    static final String MAIN_PACKAGE = "dev.r2hack.main";
    static final String STOCK_PACKAGE = "com.bullb.r2d2_nanopisystem";

    static final long STATION_CONNECT_TIMEOUT_MS = 60_000;
    static final long STATION_LOST_TIMEOUT_MS = 90_000;

    static final long GIN_POLL_MS = 5_000;            // same cadence as the stock app
    static final long HEARTBEAT_TIMEOUT_MS = 10_000;  // main app counts as dead after this
    static final long FIRST_LAUNCH_DELAY_MS = 15_000;
    static final long RELAUNCH_DELAY_MS = 5_000;
    static final int CRASH_LIMIT = 3;                 // this many deaths ...
    static final long CRASH_WINDOW_MS = 120_000;      // ... within this window => rollback or safe mode
    static final long PROBATION_MS = 60_000;          // new main build must stay alive this long

    private Config() {}
}
