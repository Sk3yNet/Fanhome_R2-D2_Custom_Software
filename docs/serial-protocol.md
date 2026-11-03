# Main board ↔ controller serial protocol

The Android main board talks to the robot's motor/LED controller over a UART using
newline-delimited JSON. This is what R2 Agent speaks on the robot's behalf.

## Transport
- Device: `/dev/ttyS2` (H3 UART2), **115200 8N1**. The robot's own `/system/lib/libr2d2.so`
  opens the port and sets the baud rate (see `apps/agent/.../SerialPortOrangePi.java`).
- Framing: **one JSON object per line**, terminated by `\n`.
- Lines that don't start with `{` or are 2 characters or shorter are ignored.
- Every message has a `cmd` field.

## Main board → controller
| cmd | extra fields | notes |
|---|---|---|
| `ready` | — | sent once when the software starts |
| `gin` | — | status poll, **every 5 s**; the reply is a `gin` object (below) |
| `move` | `power`, `angle` | drive; ignored while charging |
| `head-angle` | `angle` | ignored while charging |
| `head-shift` | `angle` | ignored while charging |
| `head-dir` | `dir` | ignored while charging |
| `mode` | `mode` (see table) | some modes are blocked while charging |
| `projector` | `mode` | |
| `arm` | `power` | |
| `lightsaber` | `power` | |
| `led` | any of `r`, `b`, `y`, `g` | leave a field out to keep that LED unchanged |
| `lcd` | any of `s`, `l` | short / long LCD; leave a field out to keep it unchanged |
| `d-head-power` | `power` | |
| `d-leg-power` | `power` | |
| `reset-wdt` | — | probably a watchdog reset |
| `debug` | — | |
| `shut-down` | — | power off |
| `status` | — | purpose unknown |

### `mode` values
0 stop · 1 wake · 2 turn around · 3 turn left · 4 turn right · 5 go forward · 6 lightsaber ·
7 who-are-you · 8 not-recognized · 9 patrol · 10 dance · 12 circle · 13 flash front LCD ·
14 flash back LCD · 15 shake head · 16 hand · 17 short LCD · 18 long LCD · 19 projector 1 · 20 projector 2

Blocked while charging: 1, 2, 3, 4, 5, 9, 10, 12, 15.

## Controller → main board
| cmd | fields | meaning |
|---|---|---|
| `gin` | `batt`, `charging-status` (0 no / 1 charging / 2 charged), `arm`, `lightsaber`, `projector`, `head`, `mode`, `status`, `lcd_l`, `lcd_s` (≥2 = on), `error` | robot state, reply to `gin` |
| `play_sound` | `sound_id`, `interrupt` (1 = interrupt current) | the controller asks the main board to play a sound |
| `ready` | — | controller is ready |
| `btn` | `value` | 1 power off · 2 toggle Wi-Fi hotspot · 3 toggle pairing · 4 lightsaber · 5 arm · 6 patrol |

## Open questions
- Ranges and units for `power`, `angle`, `dir`, and the `led` values.
- Whether the controller needs regular `gin` or `reset-wdt` traffic to keep running. R2 Agent keeps polling `gin`
  every 5 s to be safe.
- Not yet confirmed against a powered controller: the commands above are untested on real hardware.
