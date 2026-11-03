# Roadmap

Planned features in priority order. Nothing here is implemented yet unless it's checked off.

The order follows dependencies. Everything that moves the robot depends on a confirmed controller
protocol. A replacement has to do at least what the factory software does before it's useful to other
owners. Security comes before other people install this. Publishing prebuilt releases depends on the
security work. Integrations come after there are features worth integrating.

Until feature parity is reached, `tools\r2.ps1 stock-on` + `reboot` switches back to the factory software
at any time; the agent stays installed.

## 1. Foundations and safety
- [ ] **Confirm the controller protocol on a powered robot.** Check the `ready`/`gin` handshake, that
      `gin` replies arrive, and that a `mode` command moves something. Then work out the value ranges
      for movement, head and LEDs, and update [docs/serial-protocol.md](docs/serial-protocol.md).
- [ ] **Controller simulator.** A fake controller that answers `gin`, accepts commands and logs them, and
      can play back button presses. It could run as an agent "sim mode" or as a PC script on the UART.
      This lets you develop and test without a robot, e.g. on a spare main board, and lets others
      contribute.
- [ ] **Motion watchdog in the agent.** If the robot is moving and no new command arrives within a few
      seconds, the agent sends stop. This lives in the agent so it still works when the main app is hung.
- [ ] **Low-battery stop:** stop movement and routines below a battery threshold reported by `gin`.
- [ ] **Event log that survives reboots,** so you can see what happened before a crash or power loss.

## 2. Feature parity with the factory software
Today R2 Main only receives controller messages and logs them, so the robot does much less than with
its factory software. Everything below is behavior the factory software has. Build the behaviors on a
small internal sequencer (timed steps of commands, head moves and sounds) that later grows into the
routine engine in section 6.

### Core behaviors
- [ ] **Actions:** wake up, turn left/right/around, go forward, walk a circle, patrol (ends after 60 s),
      dance, shake head, who-are-you, make some noise, and a "didn't understand" reaction. Each one is a
      timed sequence of head moves, controller modes and sounds.
- [ ] **Toggles:** lightsaber, arm, short/long LCD, and projector 1/2. The projector plays its audio
      clip, then switches itself off when the clip ends.
- [ ] **Drive sounds:** periodic droid sounds while driving straight, and the head re-centers after a
      forward move.
- [ ] **Stop always means stop:** starting any action cancels the one in progress and sends stop first.

### Sound
- [ ] Play droid sounds when the controller sends `play_sound`, and as part of actions (IDs 0–18, the
      projector clips 100 and 101, and two hidden extras).
- [ ] Load the sounds at runtime from the factory files on the robot, the same way as the serial library,
      so nothing gets redistributed. Later, optional user sound packs.
- [ ] Mute and volume, kept across reboots.

### Buttons, status and lights
- [ ] **Robot buttons:** power off (a clean shutdown of both the controller and Android), hotspot/Wi-Fi
      toggle, pairing mode, lightsaber, arm, patrol.
- [ ] **Status tracking from `gin`:** battery, charging (debounced when charging stops), arm, lightsaber,
      projector, LCDs, errors.
- [ ] **LED patterns:** ready, charging, charged, low battery (below 20%), patrol, sleep, pairing,
      pairing failed, connecting to Wi-Fi, face detected, power off.
- [ ] **Sleep after 3 minutes idle** (sleep lights, voice and camera paused); any action wakes the robot.

### Voice commands
- [ ] Offline voice commands for the actions and toggles above, including "Princess Leia" and
      "Skywalker" for the two projector clips. The factory software supports English, Spanish, French,
      Italian, Japanese, Brazilian Portuguese, Russian and Chinese.
- [ ] Decide between loading the factory speech models from the robot at runtime and using an open-source
      offline recognizer with its own models.
- [ ] Pause listening during projector audio, patrol, pairing and sleep.

### Camera
- [ ] **Face detection:** on a new face, play a sound and show the face LED pattern, and turn the head
      to follow the face.
- [ ] **QR code Wi-Fi setup** in pairing mode.
- [ ] Turning face detection on and off.

### Companion phone app
- [ ] Keep the official phone app working with the same local protocol: discovery, driving and actions,
      live camera video, Wi-Fi setup, renaming the robot, mute/voice/face toggles, and the list of paired
      phones.
- [ ] The original pairing has no cryptography, so treat it as an optional compatibility mode: off by
      default, or limited to pairing mode and the robot's own hotspot. See section 3.

### Identity and network
- [ ] Keep the robot's name and ID, so the phone app and Bluetooth still recognize it.
- [ ] Hotspot named like the factory one, but with a per-robot password instead of a fixed default.

## 3. Security: authenticated communication
The agent can install software and reboot the robot, and adb over Wi-Fi is a root shell, so being on
the local network should not be enough on its own.

Today:
- The HTTP API on port 7700 checks one shared token. The token is sent in plain text and built into the APK.
- adb over Wi-Fi (port 5555) has no authentication: this build has no adb key check.

### Phase 1: before other people install this
- [ ] **adb off by default.** Wi-Fi adb is switched on only by an authenticated API call, for a limited
      time, and switched off again automatically.
- [ ] **Local network only, enforced.** Reject connections from non-private source addresses and from
      outside the robot's own subnet.
- [ ] **Token out of the APK.** Each robot gets its own credentials at pairing or install time instead of a
      token built into the build. This is required before prebuilt releases can be published (section 5).

### Phase 2
- [ ] **Pairing with physical presence.** A new client is only accepted after a button press on the robot
      (`btn` event), on the serial console, or via the SD card config file (section 8). Each client gets
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
      and drop everything else, including stray system services.
- [ ] **Turn off what isn't used:** Bluetooth (unless phone app compatibility needs it), MTP, unused system
      apps and services, and any listener we don't need.
- [ ] **No untrusted content:** no WebView, no media from the network, no downloads from URLs. Only the
      robot's own sound files and our signed updates.
- [ ] **Current TLS in our apps:** bundle a modern TLS library for outbound connections (MQTT, Home
      Assistant, GitHub) instead of relying on the system's.
- [ ] **Least privilege:** keep the agent's shell commands to a minimum, give R2 Main no extra
      permissions, and sandbox plugins.
- [ ] **Fix device node permissions** at boot, e.g. world-writable UARTs.
- [ ] **Production-style build:** turn off root adb and `ro.secure=0` with a modified boot image (shares the
      boot-image work in section 8), keeping the serial console for recovery.
- [ ] **Patch the Wi-Fi stack:** rebuild wpa_supplicant/hostapd with the KRACK fixes.
- [ ] **Kernel:** get the GPL kernel source for this board and backport critical fixes. Failing that,
      rely on the firewall and on never running untrusted code.
- [ ] Document the remaining risk in the README.

## 5. Releases and tooling
- [ ] **CI:** a GitHub Actions build of both APKs on every push. This needs a Linux version of the build script.
- [ ] **Signed releases** on GitHub, so users don't have to set up a build toolchain. Depends on
      "token out of the APK" (section 3).
- [ ] **Cross-platform PC tool:** replace the PowerShell scripts with one small Python CLI (status, push,
      logs, adb, install, restore) for Windows, macOS and Linux.
- [ ] Setup script for the build toolchain (JDK, Android SDK, platform-tools).
- [ ] Later: the agent updates itself from GitHub releases. This needs signed updates (section 3).

## 6. Scripted movement routines
- [ ] Routine format (e.g. JSON): steps of `cmd` (any controller command), `wait`, `wait_until`
      (a condition on `gin` state), `repeat`, sound and LED cues. The built-in actions from section 2
      become editable routines in this format.
- [ ] Interpreter in R2 Main, with safety built in: stop if the controller reports an error or the robot
      is charging, an overall timeout, and an emergency stop (API, button, or loss of the agent connection).
- [ ] Upload, list, run and stop routines through the agent API and the PC tool.
- [ ] Triggers: robot buttons, voice commands, a schedule, or external events (see section 9).
- [ ] Test routines against the controller simulator (section 1).
- [ ] Later: record a routine by driving the robot, then play it back.

## 7. Web control page
- [ ] A phone-friendly page served by the robot: live status (battery, charging, arm/head/lightsaber),
      a joystick for driving and head, action buttons, routine triggers, an emergency stop, and the
      camera view.
- [ ] Uses the same authentication as the API (section 3).
- [ ] Later: gamepad support.

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
      built into R2 Main, or separate APKs found by an intent filter.
- [ ] **Home Assistant via MQTT discovery:** battery and charging sensors; buttons for actions and
      routines; switches for lightsaber, arm and projector; robot availability.
- [ ] Alternatively, a Home Assistant integration over a local REST/WebSocket API, using the security
      work in section 3.
- [ ] Plugin permissions, so an integration can't install software or reboot unless allowed.

## 10. Usability and housekeeping
- [ ] **Uninstall without a PC:** a button combination on the robot that switches the factory app back
      on and reboots.
