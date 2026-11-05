# Routines

A routine is a list of timed steps (sounds, head moves, controller modes, driving, lights, built-in
actions) stored as JSON on the robot. R2 Main runs it on the same sequencer as the built-in actions,
so the same rules hold: one thing runs at a time, starting anything else cancels it, and a cancelled
routine always ends with stop. Examples to copy are in [routines/](routines/).

*Built, not yet tested on the robot.* The format, interpreter and storage are covered by off-robot
tests (`apps/test.sh`); nothing here has run on real hardware yet.

## Using routines

| Do | PC tool | API (port 7700, token as usual) |
|---|---|---|
| List routines and what's running | `tools\r2.ps1 routines` | `GET /routines` |
| Show one | `tools\r2.ps1 routine greet` | `GET /routine?name=greet` |
| Upload (or replace) | `tools\r2.ps1 routine-put docs\routines\greet.json` | `POST /routine` with the JSON as the body |
| Run | `tools\r2.ps1 routine-run greet` (or `action greet`) | `POST /routine/run?name=greet` (or `POST /action?name=greet`) |
| Stop | `tools\r2.ps1 routine-stop` (or `stop`) | `POST /routine/stop` (or the emergency `POST /stop`) |
| Delete | `tools\r2.ps1 routine-delete greet` | `POST /routine/delete?name=greet` |

The web control page lists the routines as buttons under **Routines**, with what's running.
`GET /status` has `running` (the action or routine running now, or `idle`) and `last_routine` (how the
last routine ended, e.g. `greet: finished` or `greet: stopped, R2 is on the charger`).

`GET /routines` answers:
```json
{
  "routines": [{"name": "greet", "description": "...", "steps": 12, "timeout": 60,
                "while_charging": false, "replaces_action": false}],
  "actions": ["stop", "wake", "..."],
  "running": "idle",
  "last_routine": "greet: finished"
}
```
A stored file that no longer passes the checks is listed with an `error` instead and doesn't run.
Uploads are checked completely before they're saved; a bad one is refused with a 400 and a message
that names the step, e.g. `steps[3].head: 140 is outside -130 to 130`.

Routines are kept in R2 Main's data (`files/routines/<name>.json`), so they survive app updates.

### Replacing a built-in action
A routine named like one of these built-in actions replaces it everywhere: voice commands, the
robot's buttons, the phone app, the web page and `/action`:
`wake`, `turn-left`, `turn-right`, `turn-around`, `forward`, `circle`, `dance`, `shake-head`,
`who-are-you`, `noise`, `not-recognized`, `lightsaber`, `arm`, `short-lcd`, `long-lcd`, `flash-front`,
`flash-back`, `leia`, `luke`, and the sound reactions `react-clap`, `react-double-clap` and `react-loud`
([sound-reactions.md](sound-reactions.md)). Delete the routine to get the built-in one back.
[routines/shake-head.json](routines/shake-head.json) is the built-in shake-head written as a routine.

The other action names (`stop`, `patrol`, `pair`, `mute`, `unmute`, `voice-on`/`-off`, `face-on`/`-off`,
`phone-app-on`/`-off`, `liveliness-off`/`-quiet`/`-chatty`/`-full`,
`reactions-on`/`-off`) are reserved and can't be used for routines.

## Format

```json
{
  "name": "greet",
  "description": "Wake-up sound, look around, show the lightsaber",
  "timeout": 60,
  "while_charging": false,
  "steps": [
    {"sound": 8},
    {"wait": 0.6}, {"head": -40},
    {"wait": 0.6}, {"head": 40},
    {"wait": 0.6}, {"head": 0},
    {"cmd": {"cmd": "lightsaber", "power": 1}},
    {"wait_until": {"field": "lightsaber", "eq": 1}, "timeout": 10, "else": "continue"},
    {"wait": 2},
    {"cmd": {"cmd": "lightsaber", "power": 0}}
  ]
}
```

| Key | Meaning |
|---|---|
| `name` | 1–32 characters: `a-z`, `0-9` and `-`, starting with a letter or digit. |
| `description` | Optional text for listings. |
| `timeout` | Optional. Seconds the whole routine may take, 1–900 (default 120). |
| `while_charging` | Optional. `true` lets it run on the charger (the controller ignores driving and head moves there anyway). Default `false`. |
| `steps` | The steps, run in order. At most 500, counting nested ones. |

Steps run back to back with no pause between them; put `wait` steps where the robot needs time (the
factory actions use 0.6 s between head moves). Unknown keys are refused, so typos don't go unnoticed.

| Step | Example | What it does |
|---|---|---|
| `wait` | `{"wait": 1.5}` | Pause, in seconds (0–600). |
| `sound` | `{"sound": 7}` · `{"sound": 1, "until_done": true}` | Play a droid sound by number (as `play_sound` numbers them; 100 and 101 are the projector clips). It interrupts the sound playing. With `until_done` the routine waits for it to end (up to 60 s). |
| `head` | `{"head": -45}` | Turn the head to an angle, −130 to 130 degrees from forward (positive to the right). |
| `mode` | `{"mode": 3}` | A controller mode (0–20), e.g. 2 turn around, 3 left, 4 right, 5 forward, 12 circle, 0 stop. See [serial-protocol.md](serial-protocol.md). |
| `drive` | `{"drive": {"power": 30, "angle": 0, "seconds": 2}}` | Drive for 0.1–60 s, then stop. `power` 0–100, `angle` 0–359 (0 forward, 180 reverse, 90 turns right on the spot). The move is repeated every 0.5 s, so the agent's motion watchdog keeps working. |
| `led` | `{"led": {"r": 2, "b": 2}}` | Status lights, any of `r`, `b` (front), `y`, `g` (back): 0 unchanged, 1 off, 2 on, 3/4 slow flash, 5/6 fast flash. |
| `cmd` | `{"cmd": {"cmd": "lcd", "s": 2, "l": 1}}` | Any controller command, sent as is through the agent's motion guard. `shut-down`, `ready`, `reset`, `update` and `upgrade` are refused. |
| `action` | `{"action": "dance"}` | Run a built-in action from the list above, in place. Always the built-in one, even if a routine replaces it. |
| `wait_until` | `{"wait_until": {"field": "arm", "eq": 1}, "timeout": 5, "else": "stop"}` | Wait for the robot's state. `field`: `battery`, `charging` (0 no, 1 charging, 2 charged), `arm`, `lightsaber`, `projector`, `lcd_s`, `lcd_l` (2 or more is on), from the controller's `gin` report (every 5 s). One comparison: `eq`, `ne`, `lt`, `le`, `gt`, `ge`. `timeout` 0.1–600 s (default 30). `else`: `stop` ends the routine when it times out (default), `continue` goes on with the next step. A field not reported yet counts as −1. |
| `repeat` | `{"repeat": 3, "steps": [...]}` | Run the inner steps 1–1000 times. Nests up to 4 deep. |
| `routine` | `{"routine": "sentry"}` | Run another stored routine's steps in place (up to 4 deep). The outer routine's timeout and `while_charging` apply. |

## Safety
The interpreter checks these between steps and every 0.1 s while it waits or drives, and ends the
routine (then stops the robot) when one trips:

- **Overall timeout** (`timeout` above).
- **Controller error:** `gin` reports an error that wasn't there when the routine started.
- **Charger:** R2 is charging or charged on the dock, unless `while_charging` is set.
- **Low battery:** below 10% and not charging (the agent's limit for driving).
- **Stop:** `POST /stop`, `POST /routine/stop`, the "stop" voice command, the phone app's stop, or
  starting any other action or routine.
- **Agent connection lost:** if R2 Main loses its bridge to the agent, it cancels what's running.

On top of that, every command still passes through the agent's motion guard (watchdog, head limits,
low-battery refusal; see [architecture.md](architecture.md)). A built-in action run as an `action` step
runs to its end before the next check (the longest, `leia`/`luke`, up to about a minute); stop still
cancels it at once.

## Examples
- [greet.json](routines/greet.json): sound, look around, lightsaber on and off, waiting for `gin` to confirm.
- [sentry.json](routines/sentry.json): `repeat` and `led`; runs on the charger too.
- [back-and-forth.json](routines/back-and-forth.json): `drive` forward and back.
- [shake-head.json](routines/shake-head.json): a built-in action as a routine (replaces it when uploaded).
- [show-off.json](routines/show-off.json): built-in actions in a row, then `sentry` (upload that first).
