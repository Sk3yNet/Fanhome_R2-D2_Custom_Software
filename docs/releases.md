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

The one-time switch, over adb (`tools\r2.ps1 adb` first), with the new `R2Agent.apk` and `R2Main.apk` in
`apps\out\`. *Not yet tested on the robot.*

1. **Main app:** `adb shell pm uninstall dev.r2hack.main`, then `tools\r2.ps1 push-main`. This clears the
   main app's own settings (sounds, voice keywords, the phone-app switch). The stored "last good" copy
   for rollback is still signed with the old key, so a rollback fails until the new build has passed
   probation once.
2. **Agent:** `adb shell pm uninstall dev.r2hack.agent` removes an over-the-air agent update, if there is
   one, and falls back to the `/system` copy. Then `tools\install-on-robot.ps1 refresh-agent` replaces the
   `/system` copy and reboots. For apps in `/system`, Android 4.4 accepts a new certificate at boot and
   keeps the app's data, so the agent should keep its API token and Wi-Fi settings. This is the part that
   most needs checking on the robot.

From then on the robot takes updates from the new signer only.

### Later: switching without the manual steps

This is a plan, not built yet.

- **Pin the source.** When the agent updates itself from GitHub releases (roadmap section 5), it stores
  which repository it follows and the certificate SHA-256 it expects. The release notes already publish
  that hash.
- **A switch command.** `tools\r2.ps1 switch-source <owner/repo>` downloads that repository's latest
  release, shows the certificate, and asks before it continues. It then runs the steps above over
  adb and points the agent at the new repository. The agent sits in `/system`, so this step needs adb.
- **Keep settings.** Before uninstalling main, the command saves main's settings through the agent and
  restores them after the reinstall.
