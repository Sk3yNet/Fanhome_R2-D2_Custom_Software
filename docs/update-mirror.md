# Updating R2 through a local mirror

R2 runs Android 4.4, whose TLS stack can't connect to GitHub any more, so its self-update
([security.md](security.md#self-update-from-github-releases)) can't download releases by itself. A
**mirror** fixes that: something on your network downloads each release from GitHub and serves it to R2
over plain HTTP. Two things can be the mirror:

- the **Home Assistant add-on** in [`homeassistant/r2_update_mirror`](../homeassistant/r2_update_mirror),
  which runs all the time and keeps up with new releases (this page);
- **your PC**, with `tools\r2.ps1 update-fetch` and `update-serve` ([releases.md](releases.md)).

Either way R2 asks the mirror instead of GitHub, then checks the files exactly as before. *Built, not yet
tested on the robot.*

## Why plain HTTP is fine here

The mirror is only a delivery route. R2 installs an APK from it only if:

1. its size and SHA-256 match what the mirror's `latest.json` says (this only catches damaged downloads),
2. every file in it is signed by one certificate that R2 trusts: its own, or one added with `trust-add`,
3. it is the right app (`dev.r2hack.main` or `dev.r2hack.agent`), and
4. its versionCode is newer than the installed one, so a mirror can't downgrade R2.

So someone on your network who could change the files could at most stop updates; they can't install
anything you didn't sign. Setting the mirror needs R2's admin (owner) key.

The add-on adds its own checks before it serves a release: the APKs must match the release's
`SHA256SUMS`, both must be signed by the same certificate, that certificate must be the one the release
notes name, and, if you fill in `trusted_certs`, one of those. A release that fails stays off the mirror,
the previous one stays served, and the add-on's page shows why.

## Install the add-on

1. In Home Assistant: **Settings → Add-ons → Add-on store**, then **⋮ (top right) → Repositories**.
2. Add `https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software` and close the dialog.
3. Find **R2-D2 update mirror** in the store (reload the page if needed), **Install**, then **Start**.
   Turn on **Start on boot** and **Watchdog**.
   Turn on **Show in sidebar** too.
4. The **R2-D2** page in the sidebar shows R2's health, its version and any update, what the add-on has
   mirrored with each release's signing certificate, and the commands to point R2 at it. The first check
   runs when the add-on starts, then every 6 hours; **Check now** checks right away.

The page is also on port 8765 (`http://<HA IP>:8765/`) for a quick look, read-only: its buttons only work
from the sidebar, where you're signed in to Home Assistant.

This needs Home Assistant OS or a Supervised install (add-ons don't exist in Home Assistant Container or
Core; there, use the PC option).

## Point R2 at it (once)

From the PC, with Home Assistant's IP address (R2 can't look up `homeassistant.local` names):

```
tools\r2.ps1 update-mirror http://192.168.2.20:8765
tools\r2.ps1 update-check        # should say "release ... available on the mirror" or "up to date"
tools\r2.ps1 update-install      # download from the mirror, check, install R2 Main, then the agent (R2 reboots)
tools\r2.ps1 update-auto on      # optional: check every 6 hours, install while R2 is idle
```

`tools/r2.py` takes the same commands. `tools\r2.ps1 update` shows the mirror R2 uses;
`tools\r2.ps1 update-mirror off` goes back to asking GitHub directly.

R2 follows one repository (`update-source`); the mirror must list it under `repositories`. If R2's apps
were built on your PC with your own key, the releases must be signed with the same key
(see [releases.md](releases.md#one-time-setup-add-the-signing-key)), or do the one-time `switch-source` first.

## R2 in Home Assistant: updates, health, backups

Give the add-on R2's address and a token, and it shows R2's state and lets you install updates from Home
Assistant. In the add-on's **Configuration** tab:

- `robot`: R2's IP address, e.g. `192.168.2.103`.
- `robot_token`: a plain token, made once on the PC with `tools\r2.ps1 client-add home-assistant -Plain`
  (the same kind as the `r2_token` in `secrets.yaml`; you can use the same one). It is a *control* token:
  it can't change settings, keys or where updates come from.

Then, once, on the PC: `tools\r2.ps1 update-control on`. It lets control tokens start an install (off by
default). Such an install is exactly `update-install`: only a newer build, signed by a trusted certificate,
from the source and mirror R2 already uses. `update-control off` takes it back.

With the [MQTT integration](https://www.home-assistant.io/integrations/mqtt/) set up (the Mosquitto broker
add-on), these appear in Home Assistant, on R2's device if R2 Main's MQTT link is on:

| Entity | |
| --- | --- |
| **Software** (update) | R2 Main's installed version and the newest one R2 can install. It shows under **Settings → Updates** with an **Install** button. Installing reboots R2 if the agent is updated; a notification says how it went. |
| **Check for updates** (button) | checks GitHub and R2 now |
| **Reachable** (connectivity) | R2's API answers |
| **adb over Wi-Fi open** (problem) | something answers on R2's adb port 5555, which is an unauthenticated root shell |
| **Problems** (problem) | on when any problem below is found; the list is in its attributes |

The health check, every 5 minutes, reports as problems: R2 not answering, the token refused, the adb port
open while R2 says adb is off (or adb set to always on), R2 Main not running, safe mode, and the agent being
passive. The adb check connects to port 5555 itself instead of trusting R2's own report.

Settings backups (`backup_settings`, off by default): once a day the add-on saves R2's routines, taught rooms
and watch-with-me cue files, the things the token can read back and write again, and keeps the last
`backup_keep` days. They live in the add-on's folder, so Home Assistant's own backups include them. The page
lists them with a **Restore** button that writes them back to R2 (ones with the same name are replaced).
Not included: Wi-Fi, keys, MQTT and other agent settings, which need the owner key. For those, use
`tools\r2.ps1 save-settings` on the PC.

## Voice settings on the page

With `robot` and `robot_token` set, the sidebar page has a **Voice** section for R2's side of the Home Assistant
voice satellite ([home-assistant-voice.md](home-assistant-voice.md)). It shows the satellite's mode, whether Home
Assistant is connected, who may connect, what R2 last heard and last answered, and has buttons for:

| Setting | R2 API (control level) |
| --- | --- |
| Satellite: off, wake word on R2 ("hey R2-D2"), wake word in Home Assistant | `POST /assist?mode=` |
| Who may connect: only this Home Assistant (its LAN address), or anyone on R2's subnet | `POST /assist?host=` |
| Answers in the pipeline's voice, or beeps with subtitles | `beeps-off` / `beeps-on` actions |
| Volume, mute | `POST /volume`, `mute` / `unmute` |
| On-robot voice commands (off also stops listening for Home Assistant) | `voice-on` / `voice-off` |
| Beep style: generated chirps or factory sounds | `chirps-on` / `chirps-off` |

The Assist pipeline (speech to text, the conversation agent or AI, text to speech, and in `ha` mode the wake
word) stays in Home Assistant: **Settings → Voice assistants**, then R2's device page under **Wyoming Protocol**.
The page links there. Nothing in this section needs the owner key.

## Add-on options

| Option | Default | What it does |
| --- | --- | --- |
| `repositories` | `Sk3yNet/Fanhome_R2-D2_Custom_Software` | Repositories whose releases are mirrored, as `owner/name`. Add a fork here if R2 follows one. |
| `check_hours` | `6` | How often to ask GitHub for a new release. **Check now** on the page, or the Check button in Home Assistant, checks right away. |
| `prereleases` | `false` | Also mirror pre-releases (tags with a dash, such as `v0.4.0-beta.1`). |
| `keep_releases` | `2` | How many releases to keep on disk per repository. Only the newest is offered to R2. |
| `trusted_certs` | empty | SHA-256 certificates (from the release notes, colons allowed). When set, a release signed by anything else isn't mirrored. |
| `github_token` | empty | Not needed normally: only for a private repository or if GitHub rate-limits you (60 requests an hour without one; the add-on uses a few a day). A fine-grained token with read access to the repository's contents. |
| `robot` | empty | R2's IP address, for the health check, the update entity and backups, e.g. `192.168.2.103` (`http://192.168.2.103:7700` works too; the port defaults to 7700). |
| `robot_token` | empty | A plain control token for R2 (`client-add home-assistant -Plain`). |
| `ha_entities` | `true` | Publish the entities above through MQTT. |
| `backup_settings` | `false` | Back up routines, rooms and cue files daily. |
| `backup_keep` | `14` | How many daily backups to keep. |

The port is 8765; change the host side under **Network** in the add-on's settings if it's taken, and use
that port in `update-mirror`.

## The mirror format

Shared by the add-on and the PC tool, and read by the agent (`UpdateMirror.java`):

```
http://<host>:<port>/<owner>/<repo>/latest.json
http://<host>:<port>/<owner>/<repo>/<tag>/R2Main.apk
http://<host>:<port>/<owner>/<repo>/<tag>/R2Agent.apk
```

`latest.json` ([example](../homeassistant/r2_update_mirror/example-latest.json)):

| Field | |
| --- | --- |
| `repo` | `owner/name`; R2 refuses a manifest for another repository than the one it follows |
| `tag` | the release tag, also the folder name |
| `version` | optional; the versionName (else the tag without a leading `v`) |
| `published`, `url`, `cert`, `mirrored` | shown to people; R2 doesn't rely on them |
| `assets.R2Main.apk`, `assets.R2Agent.apk` | each with `path` (relative to `latest.json`'s folder, no `..`), `sha256`, `size` and optionally `version_code`. With `version_code` R2 decides "newer" by versionCode, otherwise by version name; it always checks the versionCode of the download before installing. |

Any web server that serves files in this layout works as a mirror.

## Agent API

| Route | Level | |
| --- | --- | --- |
| `POST /update/mirror?url=http://host:port[/path]` | admin | read releases from that mirror; `url=` (empty) or `url=off` goes back to GitHub |
| `GET /update` | read | shows `mirror` and `control_may_install` along with the other update settings |
| `POST /update/check` | control | ask the mirror (or GitHub) for a newer release |
| `POST /update/install` | control, if allowed | install it; a control client only after `POST /update/control?on=1` |
| `POST /update/control?on=1\|0` | admin | let control clients (Home Assistant) start installs; off by default |

`POST /update/check` and `/update/install` use the mirror when one is set.

## Testing on the robot

Still to do:

- [ ] Install the add-on on Home Assistant; its page shows the latest release and its certificate.
- [ ] `update-mirror http://<ha-ip>:8765`, then `update-check` reports the release from the mirror.
- [ ] `update-install` installs R2 Main and the agent from the mirror; R2 reboots into the new build.
- [ ] An APK signed by another key, served by a hand-made mirror, is refused.
- [ ] `update-auto on` picks up a new release on its own once the add-on has mirrored it.
- [ ] With `robot` and `robot_token` set: the page shows R2's versions and no problems; switching adb on
      (`tools\r2.ps1 adb`) shows it as open; the entities appear on R2's device in Home Assistant.
- [ ] Install from Home Assistant's Updates page is refused before `update-control on` and works after it.
- [ ] A settings backup, then deleting a routine and restoring it from the page.
- [ ] Voice section: switching the mode, "Only this Home Assistant" (HA still connects), beeps, volume and mute
      take effect on R2 and show on the page after the change.
