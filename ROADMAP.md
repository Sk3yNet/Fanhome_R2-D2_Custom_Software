# Roadmap

Planned features in priority order. Checked items are done and confirmed on the robot. Items marked *built, not yet tested* are in the code but haven't run on real hardware yet.

The order follows dependencies. Everything that moves the robot depends on a confirmed controller
protocol. A replacement has to do at least what the factory software does before it's useful to other
owners. Security comes before other people install this. Publishing prebuilt releases depends on the
security work. Integrations come after there are features worth integrating.

Until feature parity is reached, `tools\r2.ps1 stock-on` + `reboot` switches back to the factory software
at any time; the agent stays installed.

## 1. Foundations and safety
- [x] **Confirm the controller protocol on a powered robot.** `gin` replies, turns, forward, head, arm,
      lightsaber, projector, lights and displays all work, and `move` steering is mapped. See
      [docs/serial-protocol.md](docs/serial-protocol.md).
- [ ] Still open: head angle units, `head-shift`/`head-dir`, patrol and circle. (LED values are now known and documented, but not yet confirmed on the robot.)
- [ ] **Controller simulator.** A fake controller that answers `gin`, accepts commands and logs them, and
      can play back button presses. It could run as an agent "sim mode" or as a PC script on the UART.
      This lets you develop and test without a robot, e.g. on a spare main board, and lets others
      contribute. *Built, not yet tested on the robot.* The agent's simulator mode (`POST /sim?on=1` and a reboot) answers `gin`, logs every command, and can inject button presses and battery levels.
- [ ] **Motion watchdog in the agent.** If the robot is moving and no new command arrives within a few
      seconds, the agent sends stop. This lives in the agent so it still works when the main app is hung. *Built, not yet tested on the robot.*
- [ ] **Low-battery stop:** stop movement and routines below a battery threshold reported by `gin`. *Built, not yet tested on the robot.*
- [ ] **Event log that survives reboots,** so you can see what happened before a crash or power loss. *Built, not yet tested on the robot.*

## 2. Feature parity with the factory software
Everything below is behavior the factory software has. All of it is now built; none of the newest
parts have run on the robot yet. Bigger voice and vision features can move to a container: see
[docs/offload.md](docs/offload.md). Build the behaviors on a
small internal sequencer (timed steps of commands, head moves and sounds) that later grows into the
routine engine in section 6.

### Core behaviors
- [ ] **Actions:** wake up, turn left/right/around, go forward, walk a circle, patrol (ends after 60 s),
      dance, shake head, who-are-you, make some noise, and a "didn't understand" reaction. Each one is a
      timed sequence of head moves, controller modes and sounds. *Built, not yet tested on the robot.*
- [ ] **Toggles:** lightsaber, arm, short/long LCD, and projector 1/2. The projector plays its audio
      clip, then switches itself off when the clip ends. *Built, not yet tested on the robot.* The Leia clip plays with its audio.
- [ ] **Drive sounds:** periodic droid sounds while driving straight, and the head re-centers after a
      forward move. *Built, not yet tested on the robot.*
- [ ] **Stop always means stop:** starting any action cancels the one in progress and sends stop first. *Built, not yet tested on the robot.*

### Sound
- [ ] Play droid sounds when the controller sends `play_sound`, and as part of actions (IDs 0–18, the
      projector clips 100 and 101, and two hidden extras). *Built, not yet tested on the robot.* The two hidden extras aren't mapped yet.
- [ ] Load the sounds at runtime from the factory files on the robot, the same way as the serial library,
      so nothing gets redistributed. *Built, not yet tested on the robot.* Later, optional user sound packs.
- [ ] Mute and volume, kept across reboots. *Built, not yet tested on the robot.* Mute is reachable as an action; volume is set with `POST /volume`, the DLNA renderer and Home Assistant.
- [ ] **New droid chirps on the fly** instead of the same recorded confirmation beep each time (Fanhome
      owners' most common sound complaint). R2 Main generates R2-style phrases (blips, glides, warbles,
      trills) in seven moods, plus droid sounds shaped like a sentence for a beeps-only reply. Used for the
      confirmation beep (`chirps-off` brings back the factory one), the `chirp` action, `POST /chirp`,
      `{"chirp": "happy"}` routine steps and a Home Assistant button. See [docs/chirps.md](docs/chirps.md).
      *Built, not yet tested on the robot.*

### Buttons, status and lights
- [ ] **Robot buttons:** power off (a clean shutdown of both the controller and Android), hotspot/Wi-Fi
      toggle, pairing mode, lightsaber, arm, patrol. *Built, not yet tested on the robot.* Pairing mode accepts new phones for 30 s and opens the 2-minute Wi-Fi setup window (below).
- [ ] **Status tracking from `gin`:** battery, charging (debounced when charging stops), arm, lightsaber,
      projector, LCDs, errors. *Built, not yet tested on the robot.*
- [ ] **LED patterns:** ready, charging, charged, low battery (below 20%), patrol, sleep, pairing,
      pairing failed, connecting to Wi-Fi, face detected, power off. *Built, not yet tested on the robot.* Face detected waits for face detection.
- [ ] **Sleep after 3 minutes idle** (sleep lights, voice and camera paused); any action wakes the robot. *Built, not yet tested on the robot.*
- [ ] **Idle personality:** while awake and idle, random sounds from mood banks and an occasional look around, set by
      one liveliness switch (off, quiet, chatty, full) that other features share ([liveliness.md](docs/liveliness.md)). *Built, not yet tested on the robot.*

### Voice commands
- [x] Offline voice commands for the actions and toggles above, including "Princess Leia" and
      "Skywalker" for the two projector clips. The factory software supports English, Spanish, French,
      Italian, Japanese, Brazilian Portuguese, Russian and Chinese. Confirmed in English on the robot: "hey R2-D2", then a command within 15 s (the back light alternates green and yellow while listening). Other languages: the factory app's speech models for them are on the robot, and they need phrase lists.
- [x] Decide between loading the factory speech models from the robot at runtime and using an open-source
      offline recognizer with its own models. Decided: the robot's own PocketSphinx library and English model, loaded at runtime, with our own phrase list (`files/voice/keywords.txt` overrides it). Free speech goes to a container (docs/offload.md).
- [ ] Pause listening during projector audio, patrol and pairing. *Built, not yet tested on the robot.* Also while music plays. While R2 sleeps only the wake phrase counts, so "hey R2-D2" wakes him.

### Camera
- [x] **Face detection:** on a new face, play a sound and show the face LED pattern, and turn the head
      to follow the face. Confirmed on the robot. Uses Android's built-in face detector (no extra libraries) and the factory's head-following steps. The camera is mounted on its side (frames are turned a quarter turn clockwise for detection), and it needs light on the face.
- [x] ~~**QR code Wi-Fi setup** in pairing mode.~~ Dropped: Wi-Fi setup on the web page replaces it (press the pairing button, join R2's hotspot, open the page, pick the network and type its password). *Built, not yet tested on the robot.*
- [ ] Turning face detection on and off. *Built, not yet tested on the robot.* From the phone app; the setting is kept.
- [ ] **Follow me** (beyond the factory software): "hey R2-D2, follow me" locks onto the clothes under your
      face and drives after you with the camera alone, slowly and never in reverse. See
      [docs/follow-me.md](docs/follow-me.md). *Built, not yet tested on the robot.*

### Companion phone app
- [ ] Keep the official phone app working with the same local protocol: discovery, driving and actions,
      live camera video, Wi-Fi setup, renaming the robot, mute/voice/face toggles, and the list of paired
      phones. *Built, not yet tested on the robot.* Not supported: Bluetooth (off in the factory software too) and the app's software-update and controller-reset commands, which are refused.
- [ ] The original pairing has no cryptography, so treat it as an optional compatibility mode: off by
      default, or limited to pairing mode and the robot's own hotspot. See section 3. *Built, not yet tested on the robot.* New phones are accepted only on R2's hotspot or in pairing mode (as in the factory software), video only goes to an accepted phone, and `action phone-app-off` switches it off. It is on by default for now, for parity.

### Identity and network
- [ ] Keep the robot's name and ID, so the phone app and Bluetooth still recognize it. *Built, not yet tested on the robot.* `tools\r2.ps1 import-identity` copies the name, ID and paired phones from the factory app.
- [ ] Hotspot named like the factory one, but with a per-robot password instead of a fixed default. *Built, not yet tested on the robot.* The agent makes the password on first start (`tools\r2.ps1 hotspot` shows it). The pairing button brings the factory settings back for its 2-minute window, as the way back in when it's forgotten.

## 3. Security: authenticated communication
The agent can install software and reboot the robot, and adb over Wi-Fi is a root shell, so being on
the local network should not be enough on its own.

Today:
- The HTTP API on port 7700 checks one shared token. The token is sent in plain text and built into the APK.
- adb over Wi-Fi (port 5555) has no authentication: this build has no adb key check.

### Phase 1: before other people install this
- [ ] **adb off by default.** Wi-Fi adb is switched on only by an authenticated API call, for a limited
      time, and switched off again automatically. *Built, not yet tested on the robot.* `tools\r2.ps1 adb` switches it on for 30 minutes (`POST /adb?on=1`); `adb-always` keeps the old behavior for development.
- [ ] **Local network only, enforced.** Reject connections from non-private source addresses and from
      outside the robot's own subnet. *Built, not yet tested on the robot.* Non-private addresses are refused, and so are clients outside the robot's own subnet unless allowed with `tools\r2.ps1 allow-subnets`.
- [ ] **Token out of the APK.** Each robot gets its own credentials at pairing or install time instead of a
      token built into the build. This is required before prebuilt releases can be published (section 5). *Built, not yet tested on the robot.* Each robot now makes its own token (`tools\r2.ps1 fetch-token` copies it over root adb), and `build.ps1 -Release` leaves the built-in one out. Handing it over without adb comes with pairing (phase 2).

### Phase 2
- [ ] **Pairing with physical presence.** A new client is only accepted after a button press on the robot
      (`btn` event), on the serial console, or via the SD card config file (section 8). *Built, not yet tested on the robot.* (first step) The pairing button opens a 2-minute window in which the web page can set up Wi-Fi and fetch the token without one. Each client gets
      its own key.
- [ ] **Signed requests.** HMAC or public-key signatures over method, path, body hash, timestamp and
      nonce, so captured requests can't be replayed or modified. Later, TLS with a pinned
      per-robot certificate.
- [ ] **Signed updates.** Before installing, the agent checks that an uploaded APK is signed by a
      trusted key, not just that the HTTP request was authorized.
- [ ] Look into enabling adb key authentication.
- [ ] Per-client permission levels, e.g. read-only status vs. install/reboot. Revocation, and a log of
      who did what.
- [ ] Phone app compatibility mode (section 2) limited to the permissions it needs: no installs, no reboots.

## 4. Platform security: Android 4.4.2
The robot runs Android 4.4.2 (2013) on a 3.4 kernel, as an engineering build, with no security updates.
First decide whether a full upgrade is possible; if not, harden what's there.

### Evaluate a full upgrade first
- [ ] **Newer Android for the H3:** Allwinner has newer Android releases for the H3 on other boards. Check
      whether one can be adapted to this board's wiring and 512 MB of RAM, and get the kernel source for
      this board.
- [ ] **Mainline Linux on the same board:** a current kernel with security updates. The H3, the AP6212
      Wi-Fi chip, the audio codec and USB cameras have mainline drivers. R2 Agent and R2 Main would become
      Linux services, but the controller protocol, behaviors and API carry over. It could boot from an SD
      card (section 8), leaving the factory eMMC untouched.
- [ ] **A replacement board** (e.g. a current Raspberry Pi-class board) wired to the robot's controller
      UART, speaker, microphone and camera. Map the robot's connectors and power budget first.
- [ ] Compare the options on hardware support (UART, Wi-Fi, audio in and out, camera), effort,
      reversibility, and what's needed for new users, then pick one.

### If staying on Android 4.4.2: hardening
Known exposure: kernel privilege-escalation bugs (e.g. Dirty COW), the media framework (Stagefright),
the old WebView, an old TLS stack, the Wi-Fi stack (KRACK), Bluetooth (BlueBorne), root adb and
`ro.secure=0` from the engineering build, and world-writable device nodes.
- [ ] **Firewall at boot:** iptables rules that allow only the agent's ports, only from the local subnet,
      and drop everything else, including stray system services. *Built, not yet tested on the robot.*
      Opt-in (`tools\r2.ps1 harden-on`), from private addresses and allowed subnets only, as a 5-minute trial
      that removes itself unless confirmed. See [docs/hardening.md](docs/hardening.md).
- [ ] **Turn off what isn't used:** Bluetooth (unless phone app compatibility needs it), MTP, unused system
      apps and services, and any listener we don't need. *Built, not yet tested on the robot* (first step):
      Bluetooth is kept off, and `tools\r2.ps1 hardening` lists every listener and its app. Switching off
      system apps waits for that list from the robot; MTP stays, to keep USB adb safe.
- [ ] **No untrusted content:** no WebView, no media from the network, no downloads from URLs. Only the
      robot's own sound files and our signed updates.
- [ ] **Current TLS in our apps:** bundle a modern TLS library for outbound connections (MQTT, Home
      Assistant, GitHub) instead of relying on the system's.
- [ ] **Least privilege:** keep the agent's shell commands to a minimum, give R2 Main no extra
      permissions, and sandbox plugins.
- [ ] **Fix device node permissions** at boot, e.g. world-writable UARTs. *Built, not yet tested on the robot.*
      UARTs (not the console), raw memory, the eMMC, I2C/SPI and the sunxi_debug backdoor; the
      controller UART goes to the agent's user.
- [ ] **Production-style build:** turn off root adb and `ro.secure=0` with a modified boot image (shares the
      boot-image work in section 8), keeping the serial console for recovery.
- [ ] **Patch the Wi-Fi stack:** rebuild wpa_supplicant/hostapd with the KRACK fixes.
- [ ] **Kernel:** get the GPL kernel source for this board and backport critical fixes. Failing that,
      rely on the firewall and on never running untrusted code.
- [ ] Document the remaining risk in the README.

## 5. Releases and tooling
- [x] **CI:** a GitHub Actions build of both APKs on every push, using `apps/build.sh`, the Linux/macOS version of the build script.
- [ ] **Signed releases** on GitHub, so users don't have to set up a build toolchain. Depends on
      "token out of the APK" (section 3). *Built, not yet tested on the robot.* A `v*` tag builds both APKs
      without a token, signs them with the key in the repository's secrets, and publishes them; forks
      release with their own key. Setup and switching between signers: [docs/releases.md](docs/releases.md).
- [ ] **Switch between signers without manual steps** (e.g. upstream releases ↔ a fork's): a
      `switch-source` command that keeps settings. *Built, not yet tested on the robot.*
      `tools\r2.ps1 switch-source <owner/repo | folder>` checks the new certificate, backs up both apps'
      settings, reinstalls with the new key and restores them. See [docs/releases.md](docs/releases.md).
- [ ] **Cross-platform PC tool:** replace the PowerShell scripts with one small Python CLI (status, push,
      logs, adb, install, restore) for Windows, macOS and Linux.
- [ ] Setup script for the build toolchain (JDK, Android SDK, platform-tools).
- [ ] Later: the agent updates itself from GitHub releases. This needs signed updates (section 3).

## 6. Scripted movement routines
- [ ] Routine format (e.g. JSON): steps of `cmd` (any controller command), `wait`, `wait_until`
      (a condition on `gin` state), `repeat`, sound and LED cues. The built-in actions from section 2
      become editable routines in this format. *Built, not yet tested on the robot.* See
      [docs/routines.md](docs/routines.md). Also `head`, `mode`, `drive`, `action` (a built-in action) and
      `routine` (another routine) steps. A routine named like a built-in action replaces it; the built-in
      actions themselves are still Java, with `shake-head` as a worked example in `docs/routines/`.
- [ ] Interpreter in R2 Main, with safety built in: stop if the controller reports an error or the robot
      is charging, an overall timeout, and an emergency stop (API, button, or loss of the agent connection).
      *Built, not yet tested on the robot.* Also stops below 10% battery. Off-robot tests: `apps/test.sh`.
- [ ] Upload, list, run and stop routines through the agent API and the PC tool. *Built, not yet tested on the robot.*
      `GET /routines`, `POST /routine`, `POST /routine/run`, `tools\r2.ps1 routines`, and buttons on the web page.
- [ ] Triggers: robot buttons, voice commands, a schedule, or external events (see section 9).
      *Built, not yet tested on the robot.* A routine's `triggers` list: buttons 4–6, voice phrases after
      "hey R2-D2", daily times or intervals with weekdays, named events (`POST /event`, `tools\r2.ps1 event`,
      or `r2d2/<id>/event` from Home Assistant) and messages on any MQTT topic. Automatic triggers wait until
      R2 is idle. See [docs/routines.md](docs/routines.md#triggers).
- [ ] **Rooms: "go to the kitchen".** Routes taught by driving R2 from a home spot with the joystick, replayed
      as routines (the way back taught too, or mirrored after a taught turn-around), with R2 tracking where he
      is. Voice ("go to the kitchen", "go home"), Home Assistant (Go to select, Location sensor), API
      (`POST /goto`) and the web page. *Built, not yet tested on the robot.* See [docs/places.md](docs/places.md).
- [ ] **Smarter patrol:** a tour of the taught rooms instead of the factory's wander (`tour`, or the patrol
      button with `patrol_tour` on). *Built, not yet tested on the robot.* Obstacle avoidance depends on the
      sensor test: the robot has IR, ultrasonic and foot-drop sensors, and the agent now logs unknown `gin`
      fields to find out whether the main board can see them.
- [ ] Later: printed markers (AprilTags) in each room, found by a container on the camera stream, to correct
      the drift of replayed routes.
- [ ] Test routines against the controller simulator (section 1).
- [ ] Later: record a routine by driving the robot, then play it back. (Rooms record routes this way already.)

## 7. Web control page
- [ ] A phone-friendly page served by the robot: live status (battery, charging, arm/head/lightsaber),
      a joystick for driving and head, action buttons, routine triggers, an emergency stop, and the
      camera view. *Built, not yet tested on the robot.* Served at `http://<robot>:7700/`: status, joystick, head slider, action buttons and a stop button. The camera view is built too (shown only while open).
- [ ] Uses the same authentication as the API (section 3).
- [ ] **Activity presets:** one choice of Off, Quiet, Chatty or Full show for how lively R2 is on his own
      (idle chatter and head turns, reactions to loud sounds, drive sounds, how long he stays awake), on the
      web page and as a select in Home Assistant. *Built, not yet tested on the robot.* It is the
      `liveliness` setting; see [docs/liveliness.md](docs/liveliness.md).
- [ ] Gamepad support: drive with the left stick, head with the right, STOP and actions on buttons. *Built, not yet tested on the robot.* See docs/architecture.md (Web control page).

## 8. SD card installation (soft-mod)
New users should be able to put an SD card into an untouched robot and either install this software,
or run it straight from the card as a soft-mod that disappears when the card is removed.

- [ ] **Research spike (cheap, can happen any time):** check whether the H3 boot ROM tries the microSD
      slot before the eMMC on this board, and whether card-detect is wired up. The answer decides
      whether the soft-mod below can work, and it affects pairing (section 3).
- [ ] **Soft-mod mode:** a bootable SD card with its own bootloader and a boot image whose ramdisk
      bind-mounts R2 Agent/R2 Main over the factory system at boot and disables the factory app.
      Nothing is written to the eMMC; remove the card to get the untouched robot back.
- [ ] **Installer mode:** the same card, but it first writes a full eMMC backup to the card, then installs
      onto the eMMC, with an uninstall option that restores the backup.
- [ ] On-card config file for Wi-Fi network, robot name and the pairing key, so no PC tools or serial
      adapter are needed.
- [ ] Image builder script plus written instructions; check that it works on several robots.

## 9. Plugins and integrations (Home Assistant first)
- [ ] Plugin interface in R2 Main: plugins subscribe to controller events and robot state, can send
      commands and run routines, and have their own config and lifecycle. Pick a loading model:
      built into R2 Main, or separate APKs found by an intent filter. *Built, not yet tested on the robot.* Built into R2 Main
      (separate APKs need IPC and signed updates first); Home Assistant is the first plugin, plus a small
      battery-alert example. See [docs/plugins.md](docs/plugins.md).
- [ ] **Home Assistant via MQTT discovery:** battery and charging sensors; buttons for actions and
      routines; switches for lightsaber, arm and projector; robot availability. *Built, not yet tested on the robot.*
      Also mode, current action and error sensors, display, mute, voice and face switches, and volume.
      Set the broker with `tools\r2.ps1 mqtt`; it must be on the robot's subnet or an allowed one.
      Each stored routine gets a button too, and the activity presets are a select.
- [ ] **Watch with me:** R2 reacts during a film (sounds, head turns, lights) from a cue file of times,
      following a Home Assistant media player's position over MQTT, or started by hand
      (`tools\r2.ps1 watch-start`). Reactions never drive. See [docs/watch-with-me.md](docs/watch-with-me.md).
      *Built, not yet tested on the robot.* Still to do: cue files for real films (the hard part), and
      sounds tagged by mood so reactions fit better.
- [ ] Alternatively, a Home Assistant integration over a local REST/WebSocket API, using the security
      work in section 3.
- [ ] **R2 as a Home Assistant media destination** (a `media_player` entity):
  - **Audio:** play music, alerts and text-to-speech announcements through the robot's speaker, with
    volume, pause/stop, and "now playing" state. HA's MQTT integration has no media player type, so this
    needs something else: a DLNA/UPnP renderer (which HA discovers on its own), a player that Music
    Assistant supports, or a small custom integration. Confirmed on the robot (Home Assistant media and Music Assistant): R2 Main is a
    DLNA renderer (play, pause, stop, seek, volume, mute, live state through UPnP events).
  - Media from Home Assistant counts as untrusted content (section 4): decode it in a sandboxed player,
    accept only expected formats and sources, and never let it interrupt a safety stop. *Built:* streams
    are accepted only over http(s), from a private address, and from the device that sent the request.
    Android decodes in its system media server, so a separate app process would add little; a stricter
    sandbox needs the platform work in section 4.
  - Optionally play droid reactions (or turn the head toward the room) before announcements.
  - **Video (depends on the projector work in section 10):** send short clips or images to the
    holoprojector, scaled to the panel's resolution. Once the projector can be driven live, stream them;
    until then, write them as the projector's stored clip.
- [ ] **R2's camera in Home Assistant** as an MJPEG camera (`/camera.mjpg`, token as the basic-auth password). *Built, not yet tested on the robot.* The stream itself works and is upright on the web page.
- [ ] **Voice through Home Assistant:** R2 as a Wyoming voice satellite, with wake word, speech recognition
      and text to speech in containers (openWakeWord, Whisper, Piper), R2's actions as intents, and spoken
      answers through R2's speaker. The on-robot keywords stay as the fallback. See [docs/offload.md](docs/offload.md).
      *Built, not yet tested on the robot.* R2 Main is a Wyoming satellite on port 10700 (`tools\r2.ps1 assist robot|ha|off`),
      and R2's actions reach Home Assistant's own agent through custom sentences. See [docs/home-assistant-voice.md](docs/home-assistant-voice.md).
      Optional replies in droid beeps, with the text as subtitles (web page, Home Assistant sensor): `tools\r2.ps1 assist-reply beeps`. *Built, not yet tested on the robot.*
- [ ] **R2 as an endpoint for AI voice pipelines:** after R2 recognizes his wake word, he streams what
      follows to a Home Assistant Assist pipeline, so any conversation agent configured there (a local or
      cloud LLM, or Home Assistant's own) can answer, and the reply plays through R2's speaker. The wake
      word can be detected on the robot ("hey R2-D2" with the on-robot recognizer) or in Home Assistant
      (openWakeWord), and the pipeline is chosen in Home Assistant. Ideas on top: a droid chirp before
      and after each reply, the back light showing listening and thinking, the head turning toward the
      speaker, and a pipeline tool that lets the AI run R2's actions (dance, projector clips).
      *Built, not yet tested on the robot.* Any pipeline chosen in Home Assistant answers, with the wake word on the robot
      ("hey R2-D2", then only the request leaves the robot) or in Home Assistant. R2 chirps when he hears the
      wake word. The back light shows listening, thinking and speaking. An exposed `R2-D2 action` script lets
      the AI run R2's actions, and Home Assistant announcements play through R2. Not done: turning the head toward the speaker
      (the microphone has no direction), and follow-up questions without the wake word.
- [ ] **Vision containers:** face recognition (CompreFace / Double Take) and person detection (Frigate) on R2's
      camera stream, so R2 can greet people it knows. As a Home Assistant add-on or plain Docker.
- [ ] Plugin permissions, so an integration can't install software or reboot unless allowed. *Built, not yet tested on the robot.*
      Eight permissions (status, actions, drive, routines.run, routines.edit, sound, lights, settings), granted
      per plugin with `tools\r2.ps1 plugin-grant`. No permission can give a plugin installs, reboots, adb,
      Wi-Fi, pairing or tokens.

## 10. Hardware mods: holoprojector
The holoprojector is a small transmissive LCD with a white LED behind it, run by its own microcontroller.
The robot's controller only switches each projector on and off, and the clip it plays is most likely
stored on the projector board's SPI flash chip. Hardware details, options and streaming design:
[docs/holoprojector.md](docs/holoprojector.md).

### Custom content on the existing projector
- [ ] Back up the projector's flash chip with an SPI programmer, then work out the frame format
      (probably raw frames at the panel's resolution).
- [ ] A tool that converts images or a short video clip into that format and writes it back, so each
      projector plays a custom clip. Pair it with custom audio (section 2).
- [ ] Document the projector's wiring and a safe procedure: 3.3 V only, and keep the board unpowered while
      programming.

### Replacement that streams from the main board
- [ ] Measure the projector housing and lens path; find where the main board sits relative to the dome,
      what passes through the dome joint, and the projector cable's supply voltage.
- [ ] New projector controller on an ESP32-S3: drives the existing LCD first, then a 240 × 240 panel with
      its backlight removed. Still honours the controller's EN line, playing a stored default clip.
- [ ] Frame streaming from R2 Main: decode media (sandboxed), scale, encode as JPEG, and send timestamped
      frames over Wi-Fi (or a spare UART as a wired fallback), paced by the audio clock so sound and
      picture stay in sync.
- [ ] Video from Home Assistant to the projector (section 9), and image or clip steps in routines (section 6).
- [ ] Optional, if there's room: a pico DLP light engine with its own small Linux board for real video.

## 11. Usability and housekeeping
- [ ] **Uninstall without a PC:** a button combination on the robot that switches the factory app back
      on and reboots.
