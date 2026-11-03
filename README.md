# Fanhome R2-D2 Custom Software

Replacement software for the Fanhome R2-D2 build-up robot's Android main board. It's built so you can
keep developing on the robot itself: updates go over Wi-Fi, and a small always-on recovery agent keeps
the robot reachable and stops it safely if your code crashes.

> **Status: early and experimental.** The update, recovery and diagnostics layers work on real hardware.
> Talking to the motor/LED controller is implemented but **not yet confirmed against a powered robot**.
> It does **not yet have the factory software's features**: no sounds, actions, voice commands, face
> tracking or phone app support so far. You can switch back to the factory software at any time
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
   The first build creates `secrets\` with your signing key and API token. Keep that folder private and
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
   R2 Main, turns on adb over Wi-Fi, and reboots. The robot then joins its saved home Wi-Fi network,
   or brings up its own hotspot if it can't.

## Everyday use
```
powershell -ExecutionPolicy Bypass -File apps\build.ps1 -App main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 push-main
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 status
```
Other `tools\r2.ps1` commands: `log`, `logcat [text]`, `dmesg [text]`, `adb`, `serial '<json>'`,
`rollback`, `restart`, `push-agent`, `safe-on`/`safe-off`, `wifi-station`/`wifi-ap`, `stock-on`/`stock-off`,
`reboot`. The robot is found automatically by its UDP announcement, or use `-Robot <ip>`.

## Going back to factory software
- Temporarily: `tools\r2.ps1 stock-on`, then `tools\r2.ps1 reboot`. The agent stays installed but goes passive.
- Completely: `tools\install-on-robot.ps1 restore` over adb.
- Last resort: the serial console and your eMMC backup.

## Security warning
Right now the API is protected only by a shared token over plain HTTP. adb over Wi-Fi gives a root shell
with **no authentication** to anyone on the same network. Only use this on a network you trust. Better
authentication is the first item on the [roadmap](ROADMAP.md).

## License
[MIT](LICENSE). This covers the code in this repository only. The robot's factory software is not
included and is not covered by it.
