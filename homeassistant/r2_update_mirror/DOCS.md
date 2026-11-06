# R2-D2 update mirror

R2-D2's Android 4.4 can't connect to GitHub any more. This add-on downloads each new signed release of
R2's software from GitHub, checks it, and serves it to R2 on your network, so R2 can update itself.
R2 still installs only APKs signed by a certificate it trusts, so the mirror can't put anything else on it.

## Setup

1. Start the add-on and turn on **Show in sidebar**. The **R2-D2** page shows what it has mirrored.
2. On the PC, point R2 at it once: `tools\r2.ps1 update-mirror http://<this Home Assistant's IP>:8765`.
3. Optional, for R2's health and an **Install** button in Home Assistant's Updates page:
   - make a token: `tools\r2.ps1 client-add home-assistant -Plain`
   - set `robot` (R2's IP address) and `robot_token` (that token) in the Configuration tab
   - allow installs from Home Assistant: `tools\r2.ps1 update-control on`
   - the update entity and health sensors need the MQTT integration (Mosquitto broker add-on).
4. Optional: `backup_settings` saves R2's routines, rooms and cue files once a day, included in Home
   Assistant's backups, with a Restore button on the page.

You don't need `github_token` for this public repository.

Everything else: [docs/update-mirror.md](https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software/blob/main/docs/update-mirror.md).
