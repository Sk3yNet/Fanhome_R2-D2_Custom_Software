package dev.r2hack.agent;

/** Fixed settings. API tokens: see Credentials. */
final class Config {
    static final String SERIAL_DEVICE = "/dev/ttyS2";
    static final int SERIAL_BAUD = 115200;

    static final int HTTP_PORT = 7700;        // update / control API (all interfaces, token required)
    static final int BRIDGE_PORT = 7701;      // localhost only: main app <-> controller JSON lines
    static final int ANNOUNCE_PORT = 7702;    // UDP broadcast so the PC can find the robot
    static final int CAMERA_PORT = 7711;      // localhost only: R2 Main's camera, served on as /camera.mjpg

    static final String MAIN_PACKAGE = "dev.r2hack.main";
    static final String STOCK_PACKAGE = "com.bullb.r2d2_nanopisystem";

    static final long STATION_CONNECT_TIMEOUT_MS = 60_000;
    static final long STATION_LOST_TIMEOUT_MS = 90_000;
    /** How often Wi-Fi power saving is switched off again (Android turns it back on after a DHCP renewal). */
    static final long WIFI_POWER_SAVE_CHECK_MS = 60_000;

    static final long GIN_POLL_MS = 5_000;            // same cadence as the stock app
    static final long HEARTBEAT_TIMEOUT_MS = 10_000;  // main app counts as dead after this
    static final long FIRST_LAUNCH_DELAY_MS = 15_000;
    static final long RELAUNCH_DELAY_MS = 5_000;
    static final int CRASH_LIMIT = 3;                 // this many deaths ...
    static final long CRASH_WINDOW_MS = 120_000;      // ... within this window => rollback or safe mode
    static final long PROBATION_MS = 60_000;          // new main build must stay alive this long

    // MotionGuard: how long a drive command lasts without being repeated before the agent sends stop.
    static final long MOVE_HOLD_MS = 3_000;           // `move` (joystick-style, clients repeat it)
    static final long MODE_HOLD_MS = 15_000;          // controller turns, forward, circle
    static final long PATROL_HOLD_MS = 65_000;        // patrol; the stock app ends it after 60 s
    /** Head commands are capped at +/- this many degrees. The end stops are at about 135 each way. */
    static final int HEAD_LIMIT = 130;
    /** A continuous head turn (head-dir) stops this long after the last repeat. */
    static final long HEAD_HOLD_MS = 1200;
    static final int LOW_BATTERY_PCT = 10;            // below this, drive commands are refused

    static final int ADB_DEFAULT_MINUTES = 30;       // POST /adb?on=1 without minutes=
    static final int ADB_MAX_MINUTES = 240;
    static final long SETUP_WINDOW_MS = 120_000;     // pairing button: token-free Wi-Fi setup for this long

    private Config() {}
}
