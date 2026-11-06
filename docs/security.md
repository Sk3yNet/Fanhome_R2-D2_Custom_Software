# API security: keys, signed requests, permissions and updates

*Built, not yet tested on the robot.*

The agent's API on port 7700 can install software and reboot the robot, so a token sent in plain text is
not enough: anyone on the network who sees one request could reuse it. Since security phase 2:

- **Every device has its own key** and a permission level, and can be revoked on its own.
- **Requests are signed**, so a captured request can't be replayed or changed.
- **Uploads and updates must be signed by a trusted certificate** before the agent installs them.
- **The agent can update itself** from the GitHub releases of the repository it follows.

The robot still only accepts clients on its own subnet (and the extra subnets you allow), as before.

## Clients and permission levels

| Level | What it may do |
| --- | --- |
| `read` | status, logs (`/log`), camera, routine, cue, room and plugin lists, watch-with-me state, the drive lock state, boot-hardening status, update and trust status, and the emergency stop |
| `control` | read, plus actions, routines (run, upload, delete, stop), watch-with-me cues and playback state, rooms (go to, tour, teach), enabling and locking driving ([drive lock](drive-lock.md)), volume, the voice-pipeline mode, update checks, and installing updates if an admin allowed it (`update-control on`, [update-mirror.md](update-mirror.md)) |
| `admin` | everything: plugin grants and settings, boot hardening, installs, updates, reboot, shutdown, adb, Wi-Fi, hotspot, MQTT and subnet settings, raw controller commands (`/serial`, so the web page's joystick needs admin), switching the drive lock on or off, clients, trust, logcat/dmesg |

Any route not listed as read or control needs admin, so new routes start locked down
(`apps/agent/src/dev/r2hack/agent/Permissions.java`). R2 Main's plugin scopes nest inside these:
`status` is within read, and `actions`, `drive`, `routines.run`, `routines.edit`, `sound`, `lights` and
`settings` are within control.

Built-in clients:

- **`owner`**: keyed with the robot's own token (`files/api-token`, made on first start). Always admin, and it
  can't be revoked: it is the way back in. It can be replaced with `rotate-owner`.
- **`dev`**: only in your own builds (not `--release`), keyed with `secrets\agent-token.txt`. Admin.

Other clients are made by pairing or by an admin:

```
tools\r2.ps1 pair                                   # this PC gets its own admin key (pairing button first)
tools\r2.ps1 client-add home-assistant -Plain       # a control-level token for Home Assistant
tools\r2.ps1 client-add kitchen-tablet -Role read   # a signing key that can only look
tools\r2.ps1 clients                                # who has a key, their level, when they were last seen
tools\r2.ps1 client-role <id> -Role control
tools\r2.ps1 client-revoke <id>
```

Every request that changes something is written to the event log with the client's name
(`api: home-assistant [c1a2b3c4] POST /action -> 200`), except the joystick's stream of drive commands.
Refused requests are logged too, at most once a minute per address.

### Plain tokens

Home Assistant's `rest_command` and its camera can't sign requests. A client made with `-Plain` may send
its key as it is: in the `X-R2-Token` header, as the password of HTTP basic auth, or as `?token=`. Such a
token can be read off the network, so a plain client is read or control only, and a plain token never gets
more than control. The owner and dev keys are signing keys and are refused as plain tokens.

## Signed requests

A signed request carries five headers:

| Header | Value |
| --- | --- |
| `X-R2-Client` | the client id (`owner`, `dev`, or `c` + 8 hex digits) |
| `X-R2-Time` | unix seconds on the **robot's** clock; `GET /ping` returns it as `time` |
| `X-R2-Nonce` | 16 to 64 letters and digits, random, never reused |
| `X-R2-Body` | SHA-256 of the body in lowercase hex (`e3b0c442…b855` for no body) |
| `X-R2-Signature` | HMAC-SHA256 of the string below, keyed with the client's key as UTF-8 bytes, in hex |

The signed string is seven lines joined with `\n`:

```
R2-HMAC-SHA256
POST
/action
name=dance
1762264800
3f9c0d2a7b1e4c5d6e7f8a9b
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

Line 4 is the query: each parameter as `name=value`, URL-decoded (a `+` is a space), sorted as strings
(ordinal, by name then value), joined with `&`, without the `r2client`, `r2time`, `r2nonce` and `r2sig`
parameters. It is empty when there is no query.

The agent refuses a request whose time is more than 5 minutes from its own clock, whose nonce it has seen
before, or that was signed before the agent started (so a restart doesn't make old requests valid again).
When the time is off, the 401 answer includes the robot's `time`, so a client can correct its offset and
try once more. The signature covers the claimed body hash, so the agent checks the credentials before it
accepts an upload, and checks the body against the hash once it has read it.

A link that can't carry headers (the web page's camera `<img>`) can put `r2client`, `r2time`, `r2nonce`
and `r2sig` in the query instead. Such a request has no body.

Implementations: the agent (`RequestAuth.java`), the web page (the `r2sign` block in
`apps/agent/web/index.html`), and `tools\r2.ps1` (`Get-SignedHeaders`). `apps/test.sh` checks the web
page's code against the agent's with a shared test vector.

Not done yet: TLS. Signatures stop replay and tampering, but requests and answers (e.g. the hotspot
password, or a new client's key) still travel unencrypted on your network.

## Pairing and the way back in

- **Pairing button:** pressing it opens a 2-minute window in which `POST /clients/pair` (the web page's
  **Pair this device**, or `tools\r2.ps1 pair`) makes a new admin client without any credentials, and
  `GET /setup/token` hands out the owner key (`tools\r2.ps1 fetch-token`).
- **Root adb:** `tools\r2.ps1 fetch-token` falls back to reading `files/api-token` over adb.
- **Serial console**, as before.

The owner can't be revoked or demoted, and only the owner can rotate its own key, so a paired client can't
lock you out.

**After updating from a build without signed requests:** the robot token was sent in plain text until now.
Once everything works, run `tools\r2.ps1 rotate-owner` to replace it.

## Signed updates

The agent installs an APK (uploaded with `push-main` / `push-agent`, or downloaded by the self-update)
only if:

1. every file in it is signed by the same one certificate (its JAR signature, which every build has),
2. that certificate is trusted: the agent's own signing certificate, or one added with `trust-add`,
3. its package name is right (`dev.r2hack.main` for `push-main`, `dev.r2hack.agent` for `push-agent`).

Android's installer still refuses an update signed by a different key than the installed app; this check
also covers first installs and stops a validly signed but foreign APK. Rollback to R2 Main's last good build
is unchanged: that copy was checked when it was installed.

```
tools\r2.ps1 trust                      # own certificate, plus any added ones
tools\r2.ps1 trust-add <sha256>         # e.g. a fork's release certificate (from its release notes)
tools\r2.ps1 trust-remove <sha256>
```

## Self-update from GitHub releases

The agent follows one repository's releases: the one it was released from (release builds set it to the
repository that built them), `Sk3yNet/Fanhome_R2-D2_Custom_Software` otherwise.

```
tools\r2.ps1 update                     # settings, installed versions, the latest release seen
tools\r2.ps1 update-check               # ask GitHub now
tools\r2.ps1 update-install             # download, verify, install R2 Main, then the agent (R2 reboots)
tools\r2.ps1 update-auto on             # check every 6 hours, install while R2 is idle (off by default)
tools\r2.ps1 update-source owner/repo   # follow a fork; also trust-add its certificate
tools\r2.ps1 update-mirror http://192.168.2.20:8765   # read releases from a copy on the local network
```

It installs only APKs that pass the checks above and have a newer versionCode than the installed build.
R2 Main goes on probation as with `push-main`, so a crash loop rolls it back. If the agent can't reach
GitHub (Android 4.4's TLS stack and certificate store are old; the agent switches TLS 1.2 on, but GitHub's
certificates aren't trusted), serve the releases on the local network: the Home Assistant add-on or the PC
(`update-fetch` + `update-serve`) downloads them, and `update-mirror` points R2 at that copy ([update-mirror.md](update-mirror.md)).
The mirror may be plain HTTP: the same checks apply. Or push from the PC with `update-push` / `update-build`
(releases.md, "Updating from a PC"), which are checked the same way.

## Home Assistant

The MQTT integration and the voice satellite don't use this API, so they work as before. Two things do:

- **`rest_command` for R2's actions** (docs/home-assistant-voice.md): make a token with
  `tools\r2.ps1 client-add home-assistant -Plain` and put it in `secrets.yaml` as `r2_token`.
- **The camera:** the same token as the basic-auth password.

The old robot token no longer works there: it is now the owner's signing key.
