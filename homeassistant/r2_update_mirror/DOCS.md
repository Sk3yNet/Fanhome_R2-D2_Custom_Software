# R2-D2 update mirror

R2-D2's Android 4.4 can't connect to GitHub any more. This add-on downloads each new signed release of
R2's software from GitHub, checks it, and serves it to R2 on your network, so R2 can update itself.

After starting the add-on, open its web UI: it shows what it has mirrored and the one command that points
R2 at it:

```
tools\r2.ps1 update-mirror http://<this Home Assistant's IP>:8765
tools\r2.ps1 update-install
```

R2 still installs only APKs signed by a certificate it trusts, so the mirror can't put anything else on it.

Options, the mirror format and troubleshooting:
[docs/update-mirror.md](https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software/blob/main/docs/update-mirror.md).
