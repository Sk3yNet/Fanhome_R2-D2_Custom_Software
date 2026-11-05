# Fanhome R2-D2 Custom Software

Replacement software for the Fanhome R2-D2 build-up robot's Android main board. It's built so you can
keep developing on the robot itself: updates go over Wi-Fi, and a small always-on recovery agent keeps
the robot reachable and stops it safely if your code crashes.

> **Status: early and experimental.** The update, recovery and diagnostics layers work on real hardware.
> The link to the motor/LED controller works in a powered robot (status reports, lights, displays, turning
> and stopping); the other controller commands are not tested yet.
> The factory actions (dance, wake up, lightsaber, projector clips...), droid sounds, status lights, sleep,
> the robot's buttons, the official phone app's protocol, voice commands (English) and face tracking are
> rebuilt but **not yet tested on the robot**. You can switch back to the factory software at any time
> (`tools\r2.ps1 stock-on`). See [ROADMAP.md](ROADMAP.md).

Unofficial community project, not affiliated with or endorsed by Fanhome or Lucasfilm. It does not
include any of the robot's factory software. Modifying your robot may void its warranty, and you do it
at your own risk. **Take a full backup first** (below).

## How it works
Two Android 4.4 apps replace the factory app:

- **R2 Agent**: a system app that rarely changes. It owns the controller serial link, serves a
  token-protected update/diagnostics API on port 7700, joins your Wi-Fi with hotspot fallback, and
  supervises the main app.
- **R2 Main**: your robot behavior (`apps/main/src/dev/r2hack/main/Brain.java`). Updated often over Wi-Fi.

If R2 Main crashes or hangs, the agent stops the robot and restarts it. If a freshly pushed build
keeps crashing, the agent rolls back to the last build that ran cleanly. If the agent dies, R2 Main wakes
it up. Details: [docs/architecture.md](docs/architecture.md). The controller protocol:
[docs/serial-protocol.md](docs/serial-protocol.md).

## Hardware notes
- Main board: Allwinner H3, 512 MB RAM, 8 GB eMMC, AP6212 Wi-Fi/BT, running Android 4.4.2.
- **Debug UART**: the 3-pin RX/TX/GND header, 3.3 V, 115200 baud. It gives a root shell, which is the
  most reliable way in. Any 3.3 V USB-serial adapter works. Cross the wires: board TX → adapter RX,
  board RX → adapter TX. Adapters labelled from the other side's point of view, like some ESP-01
  programmers, connect TX→TX and RX→RX.
- The controller board is on `/dev/ttyS2` at 115200.

## Prebuilt releases
Tagged versions are published as signed APKs on the repository's GitHub Releases page, so you can skip the
build toolchain below if you only want to install: put `R2Agent.apk` and `R2Main.apk` in `apps\out\` and
follow [First install](#first-install). A robot updates in place only from APKs signed with the same key it
has, so switching between your own builds, these releases and a fork's takes a one-time reinstall:
[docs/releases.md](docs/releases.md).

## Setup (Windows, PowerShell)
Everything lives under `tools\` and is ignored by git:

1. **JDK 21** (e.g. Eclipse Temurin), unzipped to `tools\jdk`.
2. **Android SDK command-line tools**, unzipped to `tools\android-sdk\cmdline-tools\latest`, then:
   ```
   tools\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat "build-tools;35.0.1" "platforms;android-19"
   ```
3. **Android platform-tools** (adb), unzipped to `tools\platform-tools`.
4. Build both apps:
   ```
   powershell -ExecutionPolicy Bypass -File apps\build.ps1
   ```
   On Linux or macOS, `apps/build.sh` does the same (JDK from `JAVA_HOME`, SDK from `ANDROID_HOME`, or the
   same `tools/` folders). The first build creates `secrets\` with your signing key and API token. Keep that folder private and
   backed up: without the key you can't update the installed apps in place.

## First install
1. **Get adb.** The serial console gives a root shell straight away. Then either:
   - USB: run the commands in `tools\serial-commands\usb-device-mode.txt` so the board shows up over
     micro-USB, or
   - Wi-Fi: run `tools\serial-commands\adb-over-wifi.txt`, join the robot's hotspot, then
     `tools\platform-tools\adb connect 192.168.43.1:5555`.

   `tools\serial-cmd.ps1 -Port COMx -CommandFile <file>` sends a command file for you.
2. **Back up the whole eMMC** to `backup\mmcblk0_full.img`, for example:
   ```
   tools\platform-tools\adb pull /dev/block/mmcblk0 backup\mmcblk0_full.img
   ```
   The install script refuses to run without this file.
3. **Install:**
   ```
   powershell -ExecutionPolicy Bypass -File tools\install-on-robot.ps1 install
   ```
   This disables the factory app (nothing is deleted), puts R2 Agent in `/system/priv-app`, installs
   R2 Main, and reboots. adb over Wi-Fi is off from then on; `tools\r2.ps1 adb` switches it on for 30 minutes. The robot then joins its saved home Wi-Fi network,
   or brings up its own hotspot if it can't. The hotspot keeps its name but gets a password of its own:
   press R2's pairing button, join the hotspot with the factory password (back for 2 minutes), and open
   `http://192.168.43.1:7700/` to see it (see *Hotspot password* in [docs/architecture.md](docs/architecture.md)).

## Everyday use
```
powershell -ExecutionPolicy Bypass -File apps\build.ps1 -App main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 push-main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 status
```
Or drive it from a phone: open `http://<robot-ip>:7700/` and enter your API token. To move R2 from its
hotspot to your Wi-Fi, see *Wi-Fi setup without a QR code* in [docs/architecture.md](docs/architecture.md).

On macOS or Linux (or Windows with Python 3.8+), `tools/r2.py` does the same with the same commands:
`python3 tools/r2.py push-main`, `python3 tools/r2.py status`, and `python3 tools/r2.py install` for the
first-time install over USB (it finds adb in `tools/platform-tools/` or on your `PATH`). `--help` lists
everything; options are spelled with two dashes (`--robot`, `--user`, `--role`, `--tag`, `--yes`...).

Other `tools\r2.ps1` commands: `action <name>` (`actions` lists them), `stop`, `log`, `log-previous`,
`logcat [text]`, `dmesg [text]`, `adb [minutes]`, `adb-off`, `allow-subnets <list>`, `serial '<json>'`, `rollback`, `restart`, `push-agent`,
`safe-on`/`safe-off`, `wifi-station`/`wifi-ap`, `hotspot`, `hotspot-password`, `stock-on`/`stock-off`, `reboot`, `shutdown`, `fetch-token`, `mqtt`,
`assist off|robot|ha`, `assist-only <ip>`.

R2 also shows up in Home Assistant as a media player (DLNA), for music and announcements, and its camera can be added as an MJPEG camera (see [docs/architecture.md](docs/architecture.md)). *Not yet tested on the robot.*
He can also be a voice assistant for Home Assistant's Assist, including AI conversation agents: say "hey R2-D2" and a question, and R2 speaks the answer
(see [docs/home-assistant-voice.md](docs/home-assistant-voice.md)). *Built, not yet tested on the robot.*
With `tools\r2.ps1 mqtt <broker-ip> -User <name>` R2 joins Home Assistant through MQTT discovery: battery and
charging sensors, buttons for the actions and routines, and switches for the lightsaber, arm, projectors and displays. *Built, not yet tested on the robot.*
**Watch with me:** R2 reacts along with a film (gasps, cheers, head turns) from a cue file of times, following
your media player's position through Home Assistant, or started by hand with `tools\r2.ps1 watch-start`
(see [docs/watch-with-me.md](docs/watch-with-me.md)). *Built, not yet tested on the robot.*

Instead of the same recorded confirmation beep every time, R2 makes up a new chirp each time
(`tools\r2.ps1 action chirps-off` brings the factory beep back), and `tools\r2.ps1 chirp happy` asks for one
in a mood. See [docs/chirps.md](docs/chirps.md). *Built, not yet tested on the robot.*

Droid sounds come from the factory app on your robot and need a sound map in
`apps\main\assets\sounds.json`, which isn't included; without it every action still runs, silently. The robot is found automatically by its UDP announcement, or use `-Robot <ip>`.

## Going back to factory software
- Temporarily: `tools\r2.ps1 stock-on`, then `tools\r2.ps1 reboot`. The agent stays installed but goes passive.
- Completely: `tools\install-on-robot.ps1 restore` over adb.

`tools\install-on-robot.ps1 refresh-agent` (over adb) replaces the agent's built-in recovery copy with the
current build and reboots. Needed once when an agent update asks for a new system permission, such as the
power-off permission the power button uses.
- Last resort: the serial console and your eMMC backup.

## Security warning
Right now the API is protected only by a token over plain HTTP, and only accepts clients on the robot's own
subnet (add others, such as a Home Assistant VLAN, with `tools\r2.ps1 allow-subnets`).
Pressing the pairing button opens a 2-minute window in which anyone on the network can set up Wi-Fi and
fetch the token and the hotspot password; if R2 is on its hotspot, that hotspot also uses the factory password
during the window. The official phone app's protocol has no real authentication; turn it off with
`tools\r2.ps1 action phone-app-off` if you don't use the app. adb over Wi-Fi is off by default, but
while it's on (`tools\r2.ps1 adb`, 30 minutes) it gives a root shell with **no authentication** to anyone on the
same network. The Home Assistant voice satellite (port 10700, off by default) has no authentication
either, as Wyoming has none; pin it to Home Assistant's address with `tools\r2.ps1 assist-only <ip>`. Only use this on a network you trust. Better
authentication is the first item on the [roadmap](ROADMAP.md).

## License
[MIT](LICENSE). This covers the code in this repository only. The robot's factory software is not
included and is not covered by it.
