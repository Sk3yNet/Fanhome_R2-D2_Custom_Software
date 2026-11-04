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
| `led` | any of `r`, `b`, `y`, `g` | status lights: `r`/`b` the front light's red and blue, `g`/`y` the back light's green and yellow. 0 or left out: unchanged, 1 off, 2 on, 3/4 slow flash, 5/6 fast flash (two phases each). Front red+blue on is the violet resting colour |
| `lcd` | any of `s`, `l` | short / long dot-matrix display (the long one is on the rear), 1 = off, 2 = on; leave a field out to keep it unchanged |
| `d-head-power` | `power` | |
| `d-leg-power` | `power` | |
| `reset-wdt` | — | probably a watchdog reset |
| `debug` | — | |
| `shut-down` | — | power off |
| `status` | — | purpose unknown |

### `mode` values the controller handles
0 stop · 2 turn around · 3 turn left · 4 turn right · 5 go forward · 9 patrol · 12 walk a circle ·
13 flash front light · 14 flash back light

Movement modes are blocked while charging. The front and back "LCDs" are colour lights. Modes 13 and 14
are toggles: send the same mode again to end the flash (front flashes red/blue and rests violet; back
flashes green/yellow and rests lime green). Mode 0 does not stop them.

Richer behaviors such as dance, shake head, wake-up and who-are-you are not controller modes. The main
board builds them from timed sequences of `head-angle`, `mode`, `lightsaber`, `arm`, `projector`, `lcd`
and sounds. Recreating them is on the [roadmap](../ROADMAP.md).

## Controller → main board
| cmd | fields | meaning |
|---|---|---|
| `gin` | `batt`, `charging-status` (0 no / 1 charging / 2 charged), `arm`, `lightsaber`, `projector`, `head`, `mode`, `status`, `lcd_l`, `lcd_s` (≥2 = on), `error` | robot state, reply to `gin` |
| `play_sound` | `sound_id`, `interrupt` (1 = interrupt current) | the controller asks the main board to play a sound |
| `ready` | — | controller is ready |
| `btn` | `value` | 1 power off · 2 toggle Wi-Fi hotspot · 3 toggle pairing · 4 lightsaber · 5 arm · 6 patrol |

## Open questions
- Units for the head `angle` and `dir`. The `led` values above come from the factory software and aren't confirmed on the robot yet.
- Whether the controller needs regular `gin` or `reset-wdt` traffic to keep running. R2 Agent keeps polling `gin`
  every 5 s to be safe.
- Confirmed on hardware so far: `gin` status replies, `mode` 13 and 14 (toggles), `mode` 3, 4, 2 and 5 each
  followed by 0 (turn left, right, around, forward; stop), `lcd` `s`/`l` on and off, and `head-angle` 20 then 0 (the head turns and returns; `gin` has no
  head position in practice), and `arm` / `lightsaber` power 1 then 0 (out, back in; `gin` reports
  the lightsaber state, but if the part sticks mechanically it still reports 0). Mode 2 stopped after
  about 2 s had turned R2 about a third of a full rotation. The other commands are untested.
- Whether turns end on their own if no stop is sent.
- `move` works: `power` 20, `angle` 0 crept forward about 1 inch in roughly 2 s; `power` 50 went about
  4 inches in roughly 1.2 s, so speed rises faster than the power value (power 0 and mode 0 stop it). `angle` 180 is reverse:
  `power` 30 went back about 2.25 inches in roughly 1.8 s. `angle` 90 turns right on the spot (seen from
  behind). `angle` 270 is not a left spin: at `power` 30 for about 1.5 s it only backed up about an inch
  with a slight twist (left side 1 in, right 0.75 in), `angle` −90 spins left on the spot, so `angle` runs −180 to
  180: 0 forward, 90 right, −90 left, 180 back. In between, the controller mixes the two
  wheels: at `angle` 45 (`power` 30, about 1.9 s) the left wheel moved about 1.5 inches forward and the right
  wheel stayed still, a forward pivot to the right. At `angle` 20 (`power` 30, about 3.6 s) both wheels
  went forward, left about 2 inches and right about 1.5, a gentle right curve.
  The full `power` range and how `angle` steers are still unknown; the factory app uses angle 0 and 180 for
  driving straight.
- The projector once came on by itself (shortly after a lightsaber jam) and `gin` reported `projector` 182;
  `projector` mode 0 turned it off. What 182 means, and what started it, is unknown.
- Confirmed: `projector` mode 1 plays the Princess Leia clip once and then stops by itself, but `gin` keeps
  reporting 1 until mode 0 is sent (the factory app sends 0 when the matching audio ends). Mode 2 plays the Luke Skywalker clip the same way (reports 2).
- Where exactly the short display sits.
