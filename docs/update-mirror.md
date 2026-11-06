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
4. **Open web UI** shows what it has mirrored, the signing certificate of each release, and the command to
   point R2 at it. The first check runs when the add-on starts, then every 6 hours.

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

## Add-on options

| Option | Default | What it does |
| --- | --- | --- |
| `repositories` | `Sk3yNet/Fanhome_R2-D2_Custom_Software` | Repositories whose releases are mirrored, as `owner/name`. Add a fork here if R2 follows one. |
| `check_hours` | `6` | How often to ask GitHub for a new release. Restart the add-on to check now. |
| `prereleases` | `false` | Also mirror pre-releases (tags with a dash, such as `v0.4.0-beta.1`). |
| `keep_releases` | `2` | How many releases to keep on disk per repository. Only the newest is offered to R2. |
| `trusted_certs` | empty | SHA-256 certificates (from the release notes, colons allowed). When set, a release signed by anything else isn't mirrored. |
| `github_token` | empty | Only needed for a private repository or if GitHub rate-limits you. A fine-grained token with read access to the repository's contents. |

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
| `GET /update` | read | shows `mirror` along with the other update settings |

`POST /update/check` and `/update/install` use the mirror when one is set.

## Testing on the robot

Still to do:

- [ ] Install the add-on on Home Assistant; its page shows the latest release and its certificate.
- [ ] `update-mirror http://<ha-ip>:8765`, then `update-check` reports the release from the mirror.
- [ ] `update-install` installs R2 Main and the agent from the mirror; R2 reboots into the new build.
- [ ] An APK signed by another key, served by a hand-made mirror, is refused.
- [ ] `update-auto on` picks up a new release on its own once the add-on has mirrored it.
