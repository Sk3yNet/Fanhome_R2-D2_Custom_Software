# Roadmap

Planned features, roughly in priority order. Nothing here is implemented yet unless marked done.

## 1. Security: authenticated communication
The agent can install software and reboot the robot, and adb over Wi-Fi is a root shell, so being on
the local network should not be enough on its own.

Today:
- The HTTP API on port 7700 checks one shared token. The token is sent in plain text and built into the APK.
- adb over Wi-Fi (port 5555) has no authentication: this build has no adb key check.

Planned:
- [ ] **Pairing with physical presence.** A new client is only accepted after a button press on the robot
      (`btn` event) or a command on the serial console. Each client gets its own key instead of one
      shared token.
- [ ] **Signed requests.** HMAC or public-key signatures over method, path, body hash, timestamp and
      nonce, so captured requests can't be replayed or modified. Later, TLS with a pinned
      per-robot certificate.
- [ ] **Signed updates.** Before installing, the agent checks that an uploaded APK is signed by the
      paired developer key, not just that the HTTP request was authorized.
- [ ] **Local network only, enforced.** Reject connections from non-private source addresses and
      from outside the robot's own subnet.
- [ ] **adb off by default.** Wi-Fi adb is switched on only by an authenticated API call, for a limited
      time, and switched off again automatically. Also look into enabling adb key authentication.
- [ ] Per-client permission levels, e.g. read-only status vs. install/reboot. Revocation, and a log of
      who did what.

## 2. SD card installation (soft-mod)
New users should be able to put an SD card into an untouched robot and either install this software,
or run it straight from the card as a soft-mod that disappears when the card is removed.

Ideas to investigate:
- [ ] Check the board's boot order: whether the H3 boot ROM tries the microSD slot before the eMMC
      (Allwinner chips usually do), and whether this board's card-detect is wired up.
- [ ] **Soft-mod mode:** a bootable SD card with its own bootloader and a boot image whose ramdisk
      bind-mounts R2 Agent/R2 Main over the factory system at boot and disables the factory app.
      Nothing is written to the eMMC; remove the card to get the untouched robot back.
- [ ] **Installer mode:** the same card, but it first writes a full eMMC backup to the card, then installs
      onto the eMMC, with an uninstall option that restores the backup.
- [ ] On-card config file for Wi-Fi network, robot name and the pairing key, so no PC tools or serial
      adapter are needed.
- [ ] Image builder script plus written instructions; check that it works on several robots.

## 3. Scripted movement routines
- [ ] Routine format (e.g. JSON): steps of `cmd` (any controller command), `wait`, `wait_until`
      (a condition on `gin` state), `repeat`, sound and LED cues.
- [ ] Interpreter in R2 Main, with safety built in: stop if the controller reports an error or the robot
      is charging, an overall timeout, and an emergency stop (API, button, or loss of the agent connection).
- [ ] Upload, list, run and stop routines through the agent API; `tools\r2.ps1 routine ...`.
- [ ] Triggers: robot buttons, a schedule, or external events (see plugins).
- [ ] Later: record a routine by driving the robot, then play it back.

## 4. Plugins and integrations (Home Assistant first)
- [ ] Plugin interface in R2 Main: plugins subscribe to controller events and robot state, can send
      commands and run routines, and have their own config and lifecycle. Pick a loading model:
      built into R2 Main, or separate APKs found by an intent filter.
- [ ] **Home Assistant via MQTT discovery:** battery and charging sensors; buttons for modes and
      routines; switches for lightsaber, arm and projector; robot availability.
- [ ] Alternatively, a Home Assistant integration over a local REST/WebSocket API, using the security
      work in section 1.
- [ ] Plugin permissions, so an integration can't install software or reboot unless allowed.

## Known gaps / housekeeping
- [ ] Confirm the controller protocol on a powered robot: `ready`/`gin` handshake, value ranges for
      movement, head and LEDs.
- [ ] Cross-platform PC tools (the scripts are PowerShell/Windows only).
- [ ] Setup script for the build toolchain (JDK, Android SDK, platform-tools).
- [ ] Sound playback in R2 Main for `play_sound` requests.
