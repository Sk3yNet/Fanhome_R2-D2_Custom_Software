# Signed releases

Pushing a tag such as `v0.3.0` runs [`.github/workflows/release.yml`](../.github/workflows/release.yml). It
builds both APKs with `apps/build.sh all --release` (no API token built in), signs them with the
repository's release key, and publishes `R2Agent.apk`, `R2Main.apk` and `SHA256SUMS` as a GitHub release. A
tag with a dash, such as `v0.3.0-beta.1`, becomes a pre-release. The release notes show the SHA-256 of the
signing certificate, so anyone can see whose key a build is signed with.

The key lives only in the repository's Actions secrets. A fork has no access to them, and forks release
with their own key (see [Forks](#forks)).

## One-time setup: add the signing key

| Secret | Value |
| --- | --- |
| `R2_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `R2_KEYSTORE_PASS` | its password (store and key password are the same) |
| `R2_KEY_ALIAS` | optional; leave it out when the alias is `r2hack` |

**Recommended: use the key you already build with.** Your robot's apps are signed with
`secrets\r2hack.keystore`. If the releases use the same key, the robot updates from a release in place,
and your local builds and the releases can replace each other. In PowerShell, from the repository folder:

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\secrets\r2hack.keystore")) | Set-Clipboard
```

1. On GitHub, open the repository, then **Settings → Secrets and variables → Actions → New repository
   secret**.
2. Name `R2_KEYSTORE_BASE64`, paste the clipboard, **Add secret**.
3. Run `(Get-Content secrets\keystore-pass.txt -Raw).Trim() | Set-Clipboard`, then add a second secret named
   `R2_KEYSTORE_PASS` with that value.

Or with the GitHub CLI:

```
gh secret set R2_KEYSTORE_BASE64 --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\secrets\r2hack.keystore")))
gh secret set R2_KEYSTORE_PASS --body (Get-Content secrets\keystore-pass.txt -Raw).Trim()
```

Keep `secrets\` backed up as before. The secret can't be read back from GitHub, and without the key no
future build can update the installed apps in place.

**Alternative: a separate release key.** Make one as shown under [Forks](#forks). Robots that run your local
builds then need the one-time switch in [Switching between builds](#switching-between-builds) before they
take a release.

## Publishing a release

```
git tag v0.1.0
git push origin v0.1.0
```

Or on GitHub: **Releases → Draft a new release**, type a new tag such as `v0.1.0`, and publish. The
workflow adds the APKs and its notes below the text you wrote. Watch the run under **Actions → Release**.
If the secrets are missing, the run stops with a message that names them.

The version name is the tag without the `v`. The versionCode is still minutes since 2025-01-01, as in
local builds, so a newer release always replaces an older build signed with the same key.

To check a download: `sha256sum -c SHA256SUMS`, or `Get-FileHash R2Main.apk` on Windows. The certificate
in the notes can be compared with `keytool -list -v -keystore secrets\r2hack.keystore` (the SHA256 line,
without the colons) or with `apksigner verify --print-certs R2Main.apk`.

## Forks

A fork builds and releases with its own key and never sees the upstream key:

1. Fork the repository. In the fork's **Actions** tab, enable workflows (GitHub turns them off in new forks).
2. Make a key, or reuse your own `secrets\r2hack.keystore` as above:
   ```
   keytool -genkeypair -keystore release.keystore -alias r2hack -keyalg RSA -keysize 2048 -validity 10000 -dname CN=your-name
   ```
   (`tools\jdk\bin\keytool.exe` on Windows if Java isn't on your PATH.) Keep this file safe and out of git.
3. Add `R2_KEYSTORE_BASE64` and `R2_KEYSTORE_PASS` to the fork's own secrets, as above.
4. Push a tag. The release appears under the fork, with the fork's certificate in its notes.

The ordinary CI build (`build.yml`) needs no secrets and runs in forks and on pull requests as before.

## Switching between builds

Android installs an update only when it is signed with the same certificate as the installed app. Updates
from a different signer fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (or `INCONSISTENT_CERTIFICATES`),
and nothing on the robot changes. That covers switching from your local builds to someone's releases,
from upstream releases to a fork's, and back.

`switch-source` does the one-time switch and keeps the settings. It works over root adb, which it switches on
itself for an hour. *Built, not yet tested on the robot.*

```
tools\r2.ps1 switch-source                       # who signed the robot's apps, and which source it follows
tools\r2.ps1 switch-source someone/R2-fork       # that repository's latest release
tools\r2.ps1 switch-source someone/R2-fork -Tag v0.4.0
tools\r2.ps1 switch-source apps\out              # your own builds (or any folder with R2Agent.apk and R2Main.apk)
```

What it does:

1. **Checks the new APKs.** A release is downloaded to `apps\out\releases\` and checked against its
   `SHA256SUMS`. Both APKs must be signed with the same key, and for a release that key must be the one its
   notes name. It shows the robot's current certificate and the new one and asks you to type `yes`. Compare
   the new one with what the source publishes before you do (`-Yes` skips the question). If the robot
   already runs that signer, it stops and tells you to update with `push-main` and `push-agent` as usual.
2. **Backs up** to `secrets\switch-backup-<date>\`: the agent's settings (API token, Wi-Fi, hotspot password, MQTT, allowed
   subnets), the main app's settings (robot name and paired phones, sounds, voice, Home Assistant, routines, watch-with-me cues, taught rooms,
   your `voice/keywords.txt`) and the old APKs. The folder holds passwords, so keep it private.
3. **Main app:** puts the agent in safe mode so the main app stays stopped, uninstalls it, installs the new
   one and puts its settings back. The agent's stored "last good" copy is still signed with the old key, so
   it is deleted; the new build becomes the rollback copy after its first probation.
4. **Agent:** replaces the `/system` copy and reboots. For apps in `/system`, Android 4.4 accepts a new
   certificate at boot and keeps the app's data ("signature changed; retaining data"), and it drops an older
   over-the-air update of the agent. So the agent keeps its token and settings. If the robot has such an
   update (from `push-agent`), the new agent's versionCode must not be lower than the update's, or Android
   would keep the update; the command checks this before it starts. This is the part that most needs
   checking on the robot.
5. Checks that the robot answers with the saved token and runs the new agent, ends safe mode, and records
   the source in `secrets\robot-source.json`.

If something goes wrong:

- **The main app install fails:** the command puts the old main app and its settings back and stops before
  touching the agent.
- **The agent lost its data** (the saved token stops working after the reboot): press the pairing button
  on R2, run `tools\r2.ps1 fetch-token`, then `tools\r2.ps1 restore-settings secrets\switch-backup-<date> -Agent`.
- **Going back:** the backup folder has the old APKs, so `tools\r2.ps1 switch-source secrets\switch-backup-<date>`
  switches back.

`save-settings [folder]` and `restore-settings <folder> [-Agent]` also work on their own, e.g. for a backup
before trying something.

### By hand

The same steps over adb (`tools\r2.ps1 adb` first), with the new `R2Agent.apk` and `R2Main.apk` in
`apps\out\`. This loses the main app's settings.

1. **Main app:** `adb shell pm uninstall dev.r2hack.main`, then `tools\r2.ps1 push-main`.
2. **Agent:** `tools\install-on-robot.ps1 refresh-agent` replaces the `/system` copy and reboots. Don't
   `pm uninstall dev.r2hack.agent` first: removing an over-the-air update that is newer than the `/system`
   copy also deletes the agent's data.

From then on the robot takes updates from the new signer only.

### Later

The agent follows one repository's releases (`tools\r2.ps1 update-source`) and installs only APKs signed
by a trusted certificate (its own, or one added with `trust-add`; the release notes publish the hash), see
[security.md](security.md). `switch-source` sets both on the agent when it switches to a GitHub release.
