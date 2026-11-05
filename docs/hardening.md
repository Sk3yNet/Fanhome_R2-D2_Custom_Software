# Boot hardening

The robot runs Android 4.4.2 with no security updates (ROADMAP section 4). R2 Agent can harden it at
every boot. There are three parts:

| Part | What it does | Switch |
|---|---|---|
| **Firewall** | iptables rules that let the local network reach only the ports R2 uses, and drop everything else | `firewall=1` |
| **Device permissions** | removes world access from the UARTs, raw memory, the eMMC, I2C/SPI, and Allwinner's `sunxi_debug` root backdoor | `devices=1` |
| **Bluetooth off** | nothing in R2 uses Bluetooth (the factory software leaves it off too) | `bluetooth=1` |

**All of it is off until you switch it on**, and each part can be switched off again on its own.

```
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 hardening     # report: what's on, what listens
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 harden-on     # all three, firewall confirmed after a check
powershell -ExecutionPolicy Bypass -File tools\r2.ps1 harden-off    # undo all three
```

API: `GET /hardening` · `POST /hardening?firewall=1|0&devices=1|0&bluetooth=1|0` · `POST /hardening?all=0` ·
`POST /hardening/confirm`. `/status` shows a short `hardening` summary.

## Firewall

### What stays reachable
From private addresses (10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 link-local and fc00::/7) and the
extra subnets of `allow-subnets`:

| Port | Used by |
|---|---|
| TCP 7700 | agent API and web page |
| TCP 7710, UDP 1900 | DLNA media player in Home Assistant (SSDP discovery) |
| TCP 10700, UDP 5353 | Wyoming voice satellite (zeroconf discovery) |
| TCP 8887, 12121 | official phone app (control, video) |
| TCP 5555 | adb over Wi-Fi, only while it's switched on (`r2.ps1 adb`) |
| TCP/UDP 53, UDP 67 | DNS and DHCP for devices on R2's own hotspot |

Always allowed: loopback (R2 Main ↔ agent bridge, camera, stream relay), replies to connections the robot
opens itself (MQTT, Home Assistant media, GitHub, DNS, NTP), ICMP, and DHCP/DHCPv6 replies.
MQTT is a connection R2 opens to the broker, so it needs no open port.

The agent and R2 Main still check the robot's own subnet and the token themselves. The firewall adds a
wall in front of everything else that listens on the board: system services, debug daemons, anything
started by the factory firmware. `r2.ps1 hardening` lists every listener with its app and whether the
firewall lets it through, so you can see what it blocks.

### Why it can't lock you out
- **Trial first.** Switching the firewall on starts a 5-minute trial. Unless `POST /hardening/confirm`
  arrives in that time (and that request has to come through the firewall), the rules remove
  themselves. `harden-on` does the check and the confirm for you. Only a confirmed firewall is set up at boot.
- **All or nothing.** The rules are built in their own chain (`r2_in`, `r2_in6`), and INPUT jumps to it only
  once every rule is in place. If any rule fails (e.g. a kernel without a match module), nothing takes effect.
- **It only narrows.** The chain never accepts anything. Allowed traffic goes back to Android's own rules,
  the default policy is never changed, and OUTPUT and FORWARD (the hotspot) are untouched. The rules
  don't depend on the network, so switching between hotspot and home Wi-Fi changes nothing.
- **Reboot clears it** until the agent starts again. It isn't saved anywhere iptables would load it from.
- **Factory fallback:** in passive mode (`stock-on` + `reboot`) the agent removes the rules and applies nothing.
- **Not affected at all:** USB adb and the serial console.

### Removing it by hand
Any of these works:
- `tools\r2.ps1 harden-off` (or `POST /hardening?firewall=0`)
- USB adb or the serial console, as root: `iptables -D INPUT -j r2_in; ip6tables -D INPUT -j r2_in6`.
  That lasts until the agent starts again (an agent restart or a reboot); run `harden-off` once the API
  answers to switch it off for good.
- `stock-on` + `reboot` (factory software; the agent goes passive and removes the rules).

IPv6: if this build has no `ip6tables`, or its kernel lacks IPv6 connection tracking, IPv4 is still
filtered and `hardening` shows `ipv6: not filtered (...)`. The agent's API refuses global IPv6 clients
on its own anyway.

## Device permissions
On this build many device nodes can be read and written by any app. With `devices=1`, at every agent
start, the agent removes the "other" permission bits (for example `0666` → `0660`) from:

- `/dev/ttyS*`, the UARTs. The controller's `/dev/ttyS2` is given to the agent's own user first, and
  the agent checks that it can still open it; if not, it puts the old owner and mode back.
- `/dev/mem`, `/dev/kmem`, `/dev/port`
- `/dev/block/mmcblk*`, `/dev/block/nand*` (the eMMC and its partitions)
- `/dev/i2c-*`, `/dev/spidev*`
- `/proc/sunxi_debug/sunxi_debug`, a known backdoor in Allwinner's 3.4 kernels: any app that writes a
  magic string to it becomes root.

Left alone: `ttyS0` and the kernel console's UART (the serial console is the recovery path of last
resort), and devices that must stay open to apps (`/dev/null`, binder, the GPU, audio, logging). The
report lists every world-writable device so the list can grow once it has been checked on the robot.

Permissions reset at every reboot, and the agent applies them again when it starts. `devices=0` puts
back the owner and mode it found, right away.

## Bluetooth
`bluetooth=1` switches Bluetooth off now and at every agent start. `bluetooth=0` switches it back on,
but only if it was on when hardening switched it off.

## Not done here (yet)
- Disabling system apps and services. The listener report from a real robot comes first, so that
  only things known to be unused are switched off.
- USB/MTP mode: left alone, because changing the USB function could break USB adb, a recovery path.
- Root adb and `ro.secure=0` need a modified boot image (ROADMAP section 8).

## Robot test plan
Do this with the robot on the bench, a USB cable to the PC, and ideally the serial console connected.
At every step, if something goes wrong: `harden-off`, or USB adb `iptables -D INPUT -j r2_in`, or reboot.

1. **Before.** Update the agent (`push-agent`), then `r2.ps1 hardening`. Expect everything off.
   Save the output: the `listeners` and `world_writable` lists are what's interesting.
2. **Firewall trial without confirming.** `r2.ps1 harden-trial`. Check, within 5 minutes:
   - `r2.ps1 status` works, the web page loads, the joystick drives.
   - `r2.ps1 hardening` shows `active: true`, a `trial_seconds_left`, and `ipv6` on or a reason.
   - Wait 5 minutes without confirming. `r2.ps1 log` shows "trial not confirmed ... firewall removed",
     and `hardening` shows `active: false`.
3. **Firewall on.** `r2.ps1 harden-on`. Expect "firewall kept". Then check each of these still works:
   - Home Assistant: the MQTT device updates (battery, a button runs an action).
   - The DLNA media player plays something (TTS is quickest).
   - Voice: "hey R2-D2" and a request in robot mode, or Home Assistant's wake word in `ha` mode.
   - Official phone app: discovery, driving, video.
   - `r2.ps1 adb` connects, `adb-off` disconnects.
   - `r2.ps1 find` still hears the announcement.
   - Something blocked: a port from the `listeners` list with `reachable_through_firewall: false`,
     e.g. `Test-NetConnection <robot> -Port <port>` from the PC, should time out.
4. **Hotspot.** `r2.ps1 wifi-ap`, join R2's hotspot with a phone or PC. Check it gets an address (DHCP),
   the web page loads, and `r2.ps1 status` works. Press the pairing button and run Wi-Fi setup from the
   web page back to the home network.
5. **Reboot.** `r2.ps1 reboot`. After boot: `hardening` shows the firewall active again (`setting: true`),
   everything in step 3 still works.
6. **Device permissions.** `hardening` should list what was fixed under `devices.fixed` and nothing under
   `failed`. Check `serial_open: true` and a recent `last_gin` in `status`, drive a little, then reboot
   and check again (the agent re-opens the UART after the change). Over adb: `ls -l /dev/ttyS2` shows
   the agent's user and `crw-rw----`. Check the serial console still gives a shell.
   If the controller link fails: `POST /hardening?devices=0` and report what `failed` said.
7. **Bluetooth.** `hardening` shows `bluetooth.on: false`.
8. **Undo.** `r2.ps1 harden-off`. `hardening` shows everything off, `adb shell iptables -S` has no `r2_in`,
   `ls -l /dev/ttyS2` shows the original mode. Reboot and check nothing comes back.
9. **Factory fallback.** With hardening on, `stock-on` + `reboot`: the factory app works and
   `iptables -S` (over USB adb) has no `r2_in`. `stock-off` + `reboot` goes back.

Report back: the step 1 lists, anything that stopped working, and the `ipv6` line.
