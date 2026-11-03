# Roadmap

Planned features in priority order. Nothing here is implemented yet unless it's checked off.

The order follows dependencies. Everything that moves the robot depends on a confirmed controller
protocol. Security comes before other people install this. Publishing prebuilt releases depends on the
security work. Integrations come after there are features worth integrating.

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

## 2. Security: authenticated communication
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
      token built into the build. This is required before prebuilt releases can be published (section 3).

### Phase 2
- [ ] **Pairing with physical presence.** A new client is only accepted after a button press on the robot
      (`btn` event), on the serial console, or via the SD card config file (section 7). Each client gets
      its own key.
- [ ] **Signed requests.** HMAC or public-key signatures over method, path, body hash, timestamp and
      nonce, so captured requests can't be replayed or modified. Later, TLS with a pinned
      per-robot certificate.
- [ ] **Signed updates.** Before installing, the agent checks that an uploaded APK is signed by a
      trusted key, not just that the HTTP request was authorized.
- [ ] Look into enabling adb key authentication.
- [ ] Per-client permission levels, e.g. read-only status vs. install/reboot. Revocation, and a log of
      who did what.

## 3. Releases and tooling
- [ ] **CI:** a GitHub Actions build of both APKs on every push. This needs a Linux version of the build script.
- [ ] **Signed releases** on GitHub, so users don't have to set up a build toolchain. Depends on
      "token out of the APK" (section 2).
- [ ] **Cross-platform PC tool:** replace the PowerShell scripts with one small Python CLI (status, push,
      logs, adb, install, restore) for Windows, macOS and Linux.
- [ ] Setup script for the build toolchain (JDK, Android SDK, platform-tools).
- [ ] Later: the agent updates itself from GitHub releases. This needs signed updates (section 2).

## 4. Scripted movement routines
- [ ] Routine format (e.g. JSON): steps of `cmd` (any controller command), `wait`, `wait_until`
      (a condition on `gin` state), `repeat`, sound and LED cues.
- [ ] Interpreter in R2 Main, with safety built in: stop if the controller reports an error or the robot
      is charging, an overall timeout, and an emergency stop (API, button, or loss of the agent connection).
- [ ] Upload, list, run and stop routines through the agent API and the PC tool.
- [ ] Triggers: robot buttons, a schedule, or external events (see section 8).
- [ ] Test routines against the controller simulator (section 1).
- [ ] Later: record a routine by driving the robot, then play it back.

## 5. Sound playback
- [ ] Play sounds when the controller sends `play_sound`, and from routines.
- [ ] Load the sounds at runtime from the factory files already on the robot, the same way as the serial
      library, so nothing gets redistributed. Also support user-supplied sound packs.
- [ ] Volume control and a mute option through the API.

## 6. Web control page
- [ ] A phone-friendly page served by the robot: live status (battery, charging, arm/head/lightsaber),
      a joystick for driving and head, mode buttons, routine triggers, and an emergency stop.
- [ ] Uses the same authentication as the API (section 2).
- [ ] Later: gamepad support.

## 7. SD card installation (soft-mod)
New users should be able to put an SD card into an untouched robot and either install this software,
or run it straight from the card as a soft-mod that disappears when the card is removed.

- [ ] **Research spike (cheap, can happen any time):** check whether the H3 boot ROM tries the microSD
      slot before the eMMC on this board, and whether card-detect is wired up. The answer decides
      whether the soft-mod below can work, and it affects pairing (section 2).
- [ ] **Soft-mod mode:** a bootable SD card with its own bootloader and a boot image whose ramdisk
      bind-mounts R2 Agent/R2 Main over the factory system at boot and disables the factory app.
      Nothing is written to the eMMC; remove the card to get the untouched robot back.
- [ ] **Installer mode:** the same card, but it first writes a full eMMC backup to the card, then installs
      onto the eMMC, with an uninstall option that restores the backup.
- [ ] On-card config file for Wi-Fi network, robot name and the pairing key, so no PC tools or serial
      adapter are needed.
- [ ] Image builder script plus written instructions; check that it works on several robots.

## 8. Plugins and integrations (Home Assistant first)
- [ ] Plugin interface in R2 Main: plugins subscribe to controller events and robot state, can send
      commands and run routines, and have their own config and lifecycle. Pick a loading model:
      built into R2 Main, or separate APKs found by an intent filter.
- [ ] **Home Assistant via MQTT discovery:** battery and charging sensors; buttons for modes and
      routines; switches for lightsaber, arm and projector; robot availability.
- [ ] Alternatively, a Home Assistant integration over a local REST/WebSocket API, using the security
      work in section 2.
- [ ] Plugin permissions, so an integration can't install software or reboot unless allowed.

## 9. Usability and housekeeping
- [ ] **Uninstall without a PC:** a button combination on the robot that switches the factory app back
      on and reboots.
