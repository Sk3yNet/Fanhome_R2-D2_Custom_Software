# Changelog

## 1.2.0

- A **Voice** section on the sidebar page for R2's side of the Home Assistant voice satellite: the satellite
  mode (off, wake word on R2, wake word in Home Assistant), whether Home Assistant is connected, who may
  connect (a button pins it to this Home Assistant), answers in voice or beeps, volume, mute, the on-robot
  voice commands, the beep style, and R2's last reply. All of it works with the control token. The Assist
  pipeline itself stays in Home Assistant's **Settings → Voice assistants**; the page links there.

## 1.1.3

- Fix: "bad gateway" on the sidebar page. Its server now always starts, and the add-on also finds Home
  Assistant's token where the s6 init leaves it (MQTT entities and notifications depended on it too).

## 1.1.2

- Fix: the buttons didn't show on the sidebar page. The sidebar now has its own internal port (8099).

## 1.1.1

- Help text for every option in the Configuration tab.
- `robot` also accepts `http://192.168.2.103:7700` (the port defaults to 7700).

## 1.1.0

- The page is in Home Assistant's sidebar (ingress), with **Check now**, **Install on R2** and backup buttons.
  On port 8765 it is read-only.
- With `robot` and `robot_token`: R2's health (reachable, versions, adb over Wi-Fi port, R2 Main, safe mode)
  every 5 minutes, and through MQTT an update entity with an Install button, a Check button and health sensors.
- Optional daily backups of R2's routines, rooms and cue files, with Restore.

## 1.0.0

- First version: mirrors the latest release of each listed repository every few hours, checks it against
  SHA256SUMS and its signing certificate, and serves it to R2 over the local network.
