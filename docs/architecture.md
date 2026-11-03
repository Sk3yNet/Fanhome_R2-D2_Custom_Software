# Architecture

Two Android 4.4 apps:

| App | Package | Lives in | Job |
|---|---|---|---|
| **R2 Agent** | `dev.r2hack.agent` | `/system/priv-app` (persistent) | Owns `/dev/ttyS2`, keeps the controller polled, serves the update API, supervises the main app, Wi-Fi failover. Rarely changes. |
| **R2 Main** | `dev.r2hack.main` | normal app | Robot behavior (`Brain.java`). Updated often over Wi-Fi. Talks to the controller only through the agent's bridge (`127.0.0.1:7701`). |

## Failover layers
1. **Main app dies or hangs** (no heartbeat for 10 s): agent sends `{"cmd":"mode","mode":0}` (stop) and relaunches it.
2. **3 deaths in 2 min right after an update**: agent reinstalls the last good build (one that stayed alive for 60 s).
3. **3 deaths in 2 min with no newer build to blame**: safe mode. Agent stays up with the update API, no relaunches. `tools\r2.ps1 safe-off` resumes.
4. **Agent dies**: R2 Main sends `dev.r2hack.agent.WAKE` after 30 s without the bridge, which restarts it.
   An over-the-air agent update restarts itself (`MY_PACKAGE_REPLACED`); the very first one over the
   `/system` copy reboots the robot instead. **Bad agent update**: `adb shell pm uninstall dev.r2hack.agent`
   reverts to the `/system/priv-app` copy.
5. **Wi-Fi**: joins the robot's saved home network (highest priority); if that fails (60 s) or drops (90 s), the robot's own hotspot comes back (`192.168.43.1`).
6. **Back to factory software**: `tools\r2.ps1 stock-on` + `reboot` (agent goes passive), or `tools\install-on-robot.ps1 restore` over adb.
7. **Always available**: root serial console on the debug UART, and your full eMMC backup in `backup\`.

## Workflow
```
powershell -ExecutionPolicy Bypass -File apps\build.ps1 -App main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 push-main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 status
```
`tools\r2.ps1` finds the robot by its UDP announcement (port 7702), or use `-Robot <ip>`.

## API (port 7700, header `X-R2-Token` from `secrets\agent-token.txt`)
`GET /ping` (no token) · `GET /status` · `GET /log` · `GET /logcat?lines=N&grep=text` · `GET /dmesg?lines=N&grep=text` ·
`POST /main/install` (APK body) · `POST /main/rollback` ·
`POST /main/restart` · `POST /agent/install` (APK body) · `POST /serial` (one JSON command) ·
`POST /safe-mode?on=1|0` · `POST /wifi?mode=station|ap` · `POST /stock?enable=1|0` · `POST /reboot`

The token is plain HTTP on your LAN: fine at home, don't expose port 7700 to the internet.

## Diagnostics over Wi-Fi
| Need | Command |
|---|---|
| Agent state / event log | `tools\r2.ps1 status` · `tools\r2.ps1 log` |
| Android log (main-app crash traces) | `tools\r2.ps1 logcat` · `tools\r2.ps1 logcat AndroidRuntime -Lines 200` |
| Kernel log (read via root adb; this kernel blocks apps) | `tools\r2.ps1 dmesg` · `tools\r2.ps1 dmesg mmc` |
| Root shell, push/pull files | `tools\r2.ps1 adb`, then `tools\platform-tools\adb shell` |

The install script sets `persist.adb.tcp.port=5555`, so root adb listens on Wi-Fi port 5555 from the next boot.
On KitKat this replaces adb over USB: `adb usb` switches back until the next reboot, and
`setprop persist.adb.tcp.port 0` (serial console or adb) turns Wi-Fi adb off for good. This build has no adb key
check, so anyone on the same network can get root: keep it to your home network.
Only the serial console sees the bootloader/early kernel output, or works when Android itself doesn't boot.

## Files
- `secrets\` – signing key, its password, and the API token. Keep private; losing the key means
  the agent/main can't be updated in place (uninstall + reinstall over adb instead).
- The UART library `libr2d2.so` is not part of this repository: the agent loads the robot's own
  `/system/lib/libr2d2.so` (or extracts it from the factory app on the device).
