# Architecture

Two Android 4.4 apps:

| App | Package | Lives in | Job |
|---|---|---|---|
| **R2 Agent** | `dev.r2hack.agent` | `/system/priv-app` (persistent) | Owns `/dev/ttyS2`, keeps the controller polled, serves the update API, supervises the main app, Wi-Fi failover. Rarely changes. |
| **R2 Main** | `dev.r2hack.main` | normal app | Robot behavior (`Brain.java`). Updated often over Wi-Fi. Talks to the controller only through the agent's bridge (`127.0.0.1:7701`). |

## Safety in the agent
Every command to the controller passes through the agent's motion guard, whoever sent it (R2 Main, `/serial`):
- **Motion watchdog:** a `move` lasts 3 s unless repeated, the controller's turns/forward/circle 15 s, patrol 65 s.
  Then the agent sends stop itself, so a hung app or a lost Wi-Fi client can't leave R2 driving.
- **Low battery:** below 10% (and not charging) drive commands are refused, and driving is stopped.
- **Power button:** handled by the agent (stop, flush storage, tell the controller, shut Android down),
  so it works even if R2 Main is hung. `POST /shutdown` does the same.
- **Emergency stop:** `POST /stop` (`tools\r2.ps1 stop`) stops the robot in the agent, then cancels R2 Main's action.

## Failover layers
1. **Main app dies or hangs** (no heartbeat for 10 s): agent sends stop and relaunches it.
2. **3 deaths in 2 min right after an update**: agent reinstalls the last good build (one that stayed alive for 60 s).
3. **3 deaths in 2 min with no newer build to blame**: safe mode. Agent stays up with the update API, no relaunches. `tools\r2.ps1 safe-off` resumes.
4. **Agent dies**: R2 Main sends `dev.r2hack.agent.WAKE` after 30 s without the bridge, which restarts it.
   An over-the-air agent update restarts itself (`MY_PACKAGE_REPLACED`); the very first one over the
   `/system` copy reboots the robot instead. **Bad agent update**: `adb shell pm uninstall dev.r2hack.agent`
   reverts to the `/system/priv-app` copy.
5. **Wi-Fi**: joins the robot's saved home network (highest priority); if that fails (60 s) or drops (90 s), the robot's own hotspot comes back (`192.168.43.1`), with this robot's own password (see *Hotspot password*).
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
`GET /` (the web control page, no token; it asks for one) · `GET /ping` (no token) · `GET /status` · `GET /log` (`?previous=1`: previous boot) · `GET /logcat?lines=N&grep=text` · `GET /dmesg?lines=N&grep=text` ·
`POST /main/install` (APK body) · `POST /main/rollback` ·
`POST /main/restart` · `POST /agent/install` (APK body) · `POST /serial` (one JSON command) ·
`GET /camera.mjpg` · `GET /camera.jpg` · `POST /action?name=<action>` (runs an R2 Main action) · `POST /event?name=<event>` (routine event triggers) · `POST /stop` · `POST /safe-mode?on=1|0` ·
`POST /wifi?mode=station|ap` · `POST /stock?enable=1|0` · `POST /reboot` · `POST /shutdown` · `GET /setup` · `POST /setup/open` ·
`GET /setup/token` · `GET /wifi/scan` · `POST /wifi/join` (`{"ssid","password"}`) · `GET /wifi/last-join` · `GET /hotspot` · `POST /hotspot` (`{"password"}`; empty: a new random one) · `POST /identity` ·
`POST /sim?on=1|0` · `POST /sim/button?value=N` · `POST /sim/battery?percent=N&charging=0|1|2` ·
`POST /adb?on=1&minutes=N` · `POST /adb?on=0` · `POST /adb?always=1` · `GET /clients/allow` · `POST /clients/allow?subnets=a.b.c.d/n,...` ·
`GET /hardening` · `POST /hardening?firewall=1|0&devices=1|0&bluetooth=1|0` · `POST /hardening?all=0` · `POST /hardening/confirm` (boot hardening, see [hardening.md](hardening.md)) ·
`POST /assist?mode=off|robot|ha&host=<ip>` (Home Assistant voice satellite, see [home-assistant-voice.md](home-assistant-voice.md)) ·
`GET /routines` · `GET /routine?name=` · `POST /routine` (JSON body) · `POST /routine/run?name=` · `POST /routine/stop` · `POST /routine/delete?name=` (see [routines.md](routines.md)) ·
`GET /cues` · `GET /cue?name=` · `POST /cue` (JSON body) · `POST /cue/delete?name=` · `GET /watch` · `POST /watch` (`{"state","position","title","cue","enabled"}`; see [watch-with-me.md](watch-with-me.md)) ·
`POST /chirp?mood=<mood>` or `POST /chirp` with text as the body (a generated droid chirp, see [chirps.md](chirps.md)) ·
`GET /mqtt` · `POST /mqtt` (`{"host","port","username","password","prefix","enabled"}`) ·
`GET /places` · `GET /place?name=` · `POST /place` · `POST /place/delete?name=` · `POST /goto?place=` · `POST /tour` ·
`POST /place/here?name=` · `POST /place/teach?name=&what=there|back|turn` · `POST /place/teach/save` · `POST /place/teach/cancel` ·
`POST /places/settings` (rooms, see [places.md](places.md)) ·
`GET /plugins` · `POST /plugin?name=&enabled=1|0&grant=<permissions>` · `POST /plugin/config?name=` (JSON body) (see [plugins.md](plugins.md))

Only clients on the robot's own subnet (its Wi-Fi network or its hotspot), loopback, or IPv6 link-local are
served. A client on another private network, such as a guest network, another VLAN or a VPN, is refused
and logged. If Home Assistant or your PC sits on a different subnet, allow it:
`tools\r2.ps1 allow-subnets 10.0.20.0/24` (comma-separated; a bare address means one host; `''` clears the
list). The token travels in plain HTTP, so keep it to your home network.

**Tokens:** each robot makes its own random token on first start (`files/api-token` in the agent's data).
`tools\r2.ps1 fetch-token` copies it to `secrets\robot-token.txt` (in the pairing button's setup window
without any token, otherwise over root adb), and `r2.ps1` then
uses it. Your own builds also accept the development token from `secrets\agent-token.txt`;
`apps\build.ps1 -Release` builds without it.

## Boot hardening
Off until switched on: `tools\r2.ps1 harden-on` sets up a firewall that lets the local network reach only
R2's ports (API, media player, voice satellite, phone app, adb while it's on, hotspot DHCP/DNS), removes
world access from the UARTs, the eMMC and other risky device nodes, and keeps Bluetooth off. The firewall
starts as a 5-minute trial and only stays if a request gets through it. `harden-off` undoes all of it, and
USB adb and the serial console are never affected. Details and the robot test plan: [hardening.md](hardening.md).

## Web control page
Open `http://<robot-ip>:7700/` on a phone or PC on the same network and enter the token once. It shows
battery, charging and movement; has a joystick (repeated `move` commands while held, so the motion
watchdog stops R2 if the page or Wi-Fi goes away), a head slider, the actions, and a stop button.

A gamepad works too (browser Gamepad API, standard layout: Xbox, PlayStation and most USB or Bluetooth
pads). The left stick drives through the same repeated `move` commands, the right trigger raises the power
cap from the Max power slider up to 100, the right stick turns the head (click it to center), START
enables driving when the drive lock is on (docs/drive-lock.md), B/Circle or Back is STOP, and A, X, Y and the D-pad run actions (listed under **Gamepad** on the page). A stick has to
return to the center before it drives: after connecting, after STOP and after the page was hidden, so a
drifting stick can't start R2. Driving stops when the pad disconnects or the page is hidden or loses focus.
Chrome and Edge allow gamepads on R2's plain-http page; Firefox only allows them on https or localhost.

## Simulator
`tools\r2.ps1 sim-on` then `reboot` makes the agent use a
pretend controller instead of `/dev/ttyS2`. It answers `gin` with a status that follows your commands,
logs every command in the event log, and `sim-button <n>` (`/sim/button`) and `/sim/battery` inject button presses and
battery levels. The power button is ignored there. `sim-off` and a reboot go back to the real controller.

## Diagnostics over Wi-Fi
| Need | Command |
|---|---|
| Agent state / event log | `tools\r2.ps1 status` · `tools\r2.ps1 log` · `tools\r2.ps1 log-previous` (survives reboots) |
| Android log (main-app crash traces) | `tools\r2.ps1 logcat` · `tools\r2.ps1 logcat AndroidRuntime -Lines 200` |
| Kernel log (read via root adb; this kernel blocks apps) | `tools\r2.ps1 dmesg` · `tools\r2.ps1 dmesg mmc` |
| Root shell, push/pull files | `tools\r2.ps1 adb`, then `tools\platform-tools\adb shell` |

**adb over Wi-Fi is off by default.** This build has no adb key check, so while it's on, anyone on the same
network gets a root shell. `tools\r2.ps1 adb` asks the agent (`POST /adb?on=1`) to switch it on for 30 minutes
(`tools\r2.ps1 adb 120` for longer, up to 240), then connects; it switches itself off when the time runs out,
and calling it again restarts the timer. `adb-off` switches it off now. Every agent start switches it off too.
On KitKat Wi-Fi adb replaces adb over USB, so USB adb works whenever Wi-Fi adb is off. The `dmesg`,
`fetch-token` and `import-identity` commands switch it on by themselves.

For development on a trusted network, `tools\r2.ps1 adb-always` keeps it on across reboots (shown as
`adb_wifi_always` in `status`); `adb-off` undoes that.
Only the serial console sees the bootloader/early kernel output, or works when Android itself doesn't boot.

## Actions and sounds (R2 Main)
`Brain.java` rebuilds the factory actions as timed steps on a small sequencer: one action at a time,
and starting one cancels the last and sends stop. Run one with `tools\r2.ps1 action <name>`
(`tools\r2.ps1 actions` lists them). The robot's buttons for lightsaber, arm, patrol and Wi-Fi work too.

Sounds are played from the factory app already installed on the robot; nothing is bundled. The
sound-number map lives in `apps\main\assets\sounds.json` (`{"7": "<resource name>", ...}`), which is
not part of this repository. Without it R2 Main still runs every action, just silently.

While R2 is awake and idle he chatters and looks around now and then, as much as the **liveliness**
setting (off, quiet, chatty, full) allows: [liveliness.md](liveliness.md). The web page and Home
Assistant show it as the **Activity** presets.

## Routines (R2 Main)
Routines are JSON step lists (sounds, head moves, modes, driving, lights, built-in actions, waits on
the robot's state, repeats) that R2 Main stores and runs on the same sequencer as the actions, with
safety checks (timeout, controller error, charger, low battery). A routine named like a built-in action
replaces it. Format, API and examples: [routines.md](routines.md). The agent passes the routine API to
R2 Main over the bridge (`{"agent":"routines","seq":N,...}`, answered by `routines-reply`), and R2 Main
reports what's running (`{"agent":"running",...}`) for `/status`.

## Rooms (R2 Main)
R2 drives to rooms along routes you taught by driving him there from a home spot with the joystick, and keeps
track of where he is. Trips run as routines, so the same safety checks apply. Voice ("go to the kitchen"),
Home Assistant, the API and the web page can send him. The agent forwards drives from `/serial` (the web
page's joystick) to R2 Main as `{"agent":"drove","cmd":...}`, which records them while teaching. See
[places.md](places.md).

## Media player for Home Assistant (R2 Main)
R2 Main is a DLNA/UPnP media renderer on port 7710 (AVTransport, RenderingControl, ConnectionManager,
with UPnP events). Home Assistant's DLNA Digital Media Renderer integration discovers it by itself and
shows a media player named "R2-D2": music, text-to-speech and alerts play through R2's speaker, with
play/pause/stop/seek, volume and mute. Volume and mute are shared with the droid sounds, and the stop
action also stops the stream. The robot needs multicast on the network for discovery; if Home Assistant
doesn't find it, add it manually with `http://<robot-ip>:7710/dlna/description.xml`.

Streams are accepted only over http(s), from a private address, and only from the device that sent the
play request (Home Assistant serves its own media and text-to-speech). If your Home Assistant hands out
media URLs on a different address, set its internal URL to its LAN address.

The board's media player (Allwinner's CedarX) can't read chunked streams with no length, which is how
Music Assistant sends audio, so R2 Main fetches each stream itself and hands it to the player from
127.0.0.1:7712 as a plain stream (keeping its start and the latest 4 MB, because the player probes the
stream and reconnects). R2 advertises MP3 only, and asks Music Assistant for MP3 instead of FLAC (its
stream server picks the format from the file extension). Confirmed with Home Assistant's own media
(radio) and with Music Assistant.

## Home Assistant over MQTT (R2 Main)
This is the `home-assistant` plugin ([plugins.md](plugins.md)): it runs with the permissions it was granted,
and `tools\r2.ps1 plugin-off home-assistant` switches it off.

R2 shows up in Home Assistant as a device through MQTT discovery, using the broker Home Assistant already
uses (for example the Mosquitto add-on). Set it once:

```
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 mqtt 192.168.1.10 -User r2d2
```

That asks for the broker password and stores the settings in the agent, so R2 Main updates keep them.
`tools\r2.ps1 mqtt` shows the settings and whether R2 is connected, and `tools\r2.ps1 mqtt off` switches it
off. Other ports: `mqtt 192.168.1.10:1884`. The discovery prefix is `homeassistant` unless you send
`"prefix"` to `POST /mqtt`. Leaving out the password in `POST /mqtt` keeps the stored one; `GET /mqtt` never
shows it.

The device (named after the robot) has:
- **Sensors:** battery (%), charging, mode (ready, sleeping, pairing, patrol, phone control), the action
  that is running, the controller's error (diagnostic), and the watch-with-me state.
- **Buttons:** stop, wake up, turn left / right / around, go forward, walk a circle, patrol, dance, shake
  head, who are you, make some noise, flash front / back light, go home, tour the rooms, and one button per
  stored routine (`Routine <name>`). Rooms ([places.md](places.md)): a **Location** sensor and a **Go to** select. A routine that is saved or deleted shows up or disappears within a second or so.
- **Switches:** lightsaber, arm, projector Princess Leia, projector Skywalker, short and long display,
  mute, voice commands, face tracking, watch with me ([watch-with-me.md](watch-with-me.md)). A switch runs the matching action only if R2 isn't already in that
  state. The lightsaber, arm, projector and display states come from the controller's status, which R2
  reads every 5 s, so a switch can take a few seconds to show its new state.
- **Volume** (0–100 %), shared with the droid sounds and the media player.
- **Activity** select: the liveliness preset (off, quiet, chatty, full), see [liveliness.md](liveliness.md).
- **Availability:** R2 is shown as unavailable when R2 Main stops or loses the broker (MQTT last will).

Topics are under `r2d2/<robot id>/`: state in `battery`, `charging`, `mode`, `action`, `error`,
`volume`, `liveliness`, `switch/<name>`, `watch/state`, `watch/attributes`; commands in `action/run` (payload: the action name), `routine/run` (payload: the routine name), `switch/<name>/set`
(`ON`/`OFF`), `volume/set`, `liveliness/set` (`off`, `quiet`, `chatty` or `full`) and `watch/set` (a media player's state and position, see
[watch-with-me.md](watch-with-me.md)). When Home Assistant restarts (`homeassistant/status` = `online`), R2 sends
discovery and state again.

**Safety and security:** MQTT commands run as ordinary actions, so the agent's motion watchdog,
low-battery stop and emergency stop apply. Only the buttons, switches and select above can be used: no pairing,
no phone app switch, nothing that installs software or reboots. The broker must be on the robot's own
subnet, or in a subnet allowed with `tools\r2.ps1 allow-subnets`, the same rule as for API clients; any
other broker address is refused and shown in `tools\r2.ps1 mqtt`. Anyone who can publish to the broker
can run these actions, so give the broker a password (Home Assistant's Mosquitto add-on requires one).
The connection is plain MQTT on your network (no TLS yet: the robot's TLS stack is from 2013, see the roadmap).

## Voice satellite for Home Assistant (R2 Main)
R2 Main can be a Wyoming voice satellite on port 10700, so Home Assistant's Assist pipelines, including AI
conversation agents, answer through R2's microphone and speaker. Off by default. Setup, modes and privacy:
[home-assistant-voice.md](home-assistant-voice.md).

## Camera
R2 Main streams R2's camera as MJPEG (480×640 portrait, up to 10 frames a second). The camera is mounted on its side, so R2 turns each frame a quarter turn clockwise; the phone app's video stays as the camera delivers it, as in the factory software. It listens on
localhost only; the agent serves it at `/camera.mjpg` (live) and `/camera.jpg` (one picture) after
checking the token, so the camera has the same protection as everything else. The camera is switched on
only while someone is watching, and off again 3 s after the last viewer leaves.

Clients that can't send the `X-R2-Token` header can give the token as the password of HTTP basic
auth (any user name), or as `?token=`. The web page's camera view uses `?token=`.

**Home Assistant:** add the *MJPEG IP Camera* integration with MJPEG URL `http://<robot-ip>:7700/camera.mjpg`,
still image URL `http://<robot-ip>:7700/camera.jpg`, any user name, your token as the password, and basic
authentication.

## Modes and status lights (R2 Main)
Like the factory software, R2 Main is in one of these modes: ready, sleeping (after 3 minutes without
activity; any action wakes it), pairing (30 s after the pairing button), patrol (60 s), or phone control.
The front light shows the mode, charging and low battery (20%); the back light shows the network (home
Wi-Fi, no Wi-Fi, hotspot) or sleep. Charging switching off is debounced for 3 s.

## Voice commands and face tracking (R2 Main)
**Voice:** keyword spotting with CMU PocketSphinx, as in the factory software. The recognizer library
and English speech model are copied once from the factory app on the robot; nothing is bundled. Say
"hey R2-D2" (the back light shows R2 is listening), then a command within 15 s: turn left / right /
around, go forward, shake your head, walk a circle, dance please, make some noise, who are you,
lightsaber, move arms, patrol, stop, Princess Leia, Skywalker. Each command keeps it listening for another
15 s. It doesn't listen while asleep, patrolling, pairing, or playing a projector clip or music. To tune
the phrases or their sensitivity, put a PocketSphinx keyword file at `files/voice/keywords.txt` in R2
Main's data (phrases must use words from the model's dictionary).

**Faces:** Android's built-in face detector on the camera image, about 2.5 times a second, while R2 is
ready (not asleep, patrolling, pairing or under phone control). After a face has been there for 1.5 s,
R2 makes a sound, shows the face light and turns its head toward it in small steps. The camera may be
mounted sideways, so both orientations are tried until faces turn up in one. The phone app's face
detection switch turns it off.

Bigger voice and vision features (free speech, knowing *who* a face is) are meant to run in a container:
[docs/offload.md](docs/offload.md).

## Wi-Fi setup without a QR code
1. Press R2's pairing button. For 2 minutes, Wi-Fi setup and pairing work without the token.
2. Join R2's hotspot with your phone (or use the home network if R2 is already on it), open
   `http://192.168.43.1:7700/`, and tap **Pair this device** to save R2's token on it.
3. Under **Wi-Fi**, scan, pick your network (or type its name), enter the password and tap **Join**.
   R2 saves it as its home network and joins it. If it can't, it forgets it again and its hotspot comes
   back within about a minute; `GET /wifi/last-join` (or the event log) says what happened.

With the token, the same works at any time, also from the PC: `tools\r2.ps1 wifi-scan` and
`tools\r2.ps1 wifi-join <ssid>` (it asks for the password). The official phone app's own Wi-Fi setup
command is supported too.

## Hotspot password
The factory software gives every robot's hotspot the same password. R2 Agent keeps the factory hotspot
name but gives the hotspot a password of its own, made on the robot the first time the agent starts, like
`k7mq-x3fa-9tpe` (12 random characters without look-alikes). It's kept in the agent's data, and in
`files/hotspot-password` for the serial console and root adb.

- **See it:** `tools\r2.ps1 hotspot`, `GET /hotspot`, or **Show hotspot password** on the web page.
- **Change it:** `tools\r2.ps1 hotspot-password` (asks for one, 8 to 63 characters; Enter or `new` for a
  random one), or **New hotspot password** on the web page. A running hotspot restarts with it, so phones
  on it have to join again.
- **Forgot it:** press R2's pairing button. If R2 is on its hotspot, the hotspot restarts with its factory
  settings for the 2-minute setup window. Join it with the factory password, open
  `http://192.168.43.1:7700/` and tap **Show hotspot password** (or **Pair this device** first). After the
  window the hotspot restarts with this robot's own password. If R2 is on your home network instead, the
  token works there. If R2 is on neither, press its Wi-Fi button first to bring up the hotspot. Last
  resort: `cat /data/data/dev.r2hack.agent/files/hotspot-password` on the serial console.
- **Official phone app:** its setup flow joins the hotspot with the factory password, so use it within the
  pairing button's window, or type R2's own password into the phone's Wi-Fi settings.
- **Factory software:** `tools\r2.ps1 stock-on` puts the factory hotspot settings back, and a passive agent
  leaves them alone.

The factory settings are whatever Android's hotspot settings held the first time this agent started. If
the agent's data is ever wiped, they can't be read again; the setup window then runs the hotspot without a
password instead.

## Official phone app (R2 Main)
R2 Main speaks the factory software's local protocol: discovery broadcasts on UDP 8090, JSON commands over
a WebSocket on 8887, and camera video on 12121. Driving, the actions, toggles, renaming, mute, the paired
phones list, Wi-Fi setup and power-off work. As in the factory software, a new phone is accepted only on
R2's hotspot or in pairing mode; phones paired before are accepted any time. The protocol itself has no
encryption or real authentication, so on top of that R2 refuses the app's software-update and
controller-reset commands, and sends video only to a phone with an accepted connection.
`tools\r2.ps1 action phone-app-off` switches the whole thing off.

To keep phones that were paired with the factory software working, copy the robot's identity once:
`tools\r2.ps1 import-identity` (name, ID and paired phones, over root adb).

## Files
- `secrets\` – signing key, its password, and the API token. Keep private; losing the key means
  the agent/main can't be updated in place (uninstall + reinstall over adb instead).
  GitHub releases are signed with the key in the repository's Actions secrets
  ([releases.md](releases.md)).
- The UART library `libr2d2.so` is not part of this repository: the agent loads the robot's own
  `/system/lib/libr2d2.so` (or extracts it from the factory app on the device).
