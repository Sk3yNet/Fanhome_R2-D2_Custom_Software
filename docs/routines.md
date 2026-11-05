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
| Send an event (for `event` triggers) | `tools\r2.ps1 event doorbell` | `POST /event?name=doorbell` |

The web control page lists the routines as buttons under **Routines**, with what's running.
`GET /status` has `running` (the action or routine running now, or `idle`) and `last_routine` (how the
last routine ended, e.g. `greet: finished` or `greet: stopped, R2 is on the charger`).

`GET /routines` answers:
```json
{
  "routines": [{"name": "greet", "description": "...", "steps": 12, "timeout": 60,
                "while_charging": false, "replaces_action": false,
                "triggers": ["button 4", "at 07:30 on mon, fri (if idle)"]}],
  "actions": ["stop", "wake", "..."],
  "running": "idle",
  "last_routine": "greet: finished",
  "last_trigger": "button 4: started greet"
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
`flash-back`, `leia`, `luke`. Delete the routine to get the built-in one back.
[routines/shake-head.json](routines/shake-head.json) is the built-in shake-head written as a routine.

The other action names (`stop`, `patrol`, `pair`, `mute`, `unmute`, `voice-on`/`-off`, `face-on`/`-off`,
`phone-app-on`/`-off`, `liveliness-off`/`-quiet`/`-chatty`/`-full`) are reserved and can't be used for routines.

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
| `triggers` | Optional. What else starts it: buttons, voice phrases, schedules, events, MQTT messages. See [Triggers](#triggers). |
| `steps` | The steps, run in order. At most 500, counting nested ones. |

Steps run back to back with no pause between them; put `wait` steps where the robot needs time (the
factory actions use 0.6 s between head moves). Unknown keys are refused, so typos don't go unnoticed.

| Step | Example | What it does |
|---|---|---|
| `wait` | `{"wait": 1.5}` | Pause, in seconds (0–600). |
| `sound` | `{"sound": 7}` · `{"sound": 1, "until_done": true}` | Play a droid sound by number (as `play_sound` numbers them; 100 and 101 are the projector clips). It interrupts the sound playing. With `until_done` the routine waits for it to end (up to 60 s). |
| `chirp` | `{"chirp": "happy"}` · `{"chirp": "question", "until_done": true}` | Play a newly generated droid chirp (different every time): `ack`, `happy`, `excited`, `question`, `sad`, `worried`, `chatter` or `random`. See [chirps.md](chirps.md). Interrupts the sound playing; `until_done` waits for it to end. |
| `head` | `{"head": -45}` | Turn the head to an angle, −130 to 130 degrees from forward (positive to the right). |
| `mode` | `{"mode": 3}` | A controller mode (0–20), e.g. 2 turn around, 3 left, 4 right, 5 forward, 12 circle, 0 stop. See [serial-protocol.md](serial-protocol.md). |
| `drive` | `{"drive": {"power": 30, "angle": 0, "seconds": 2}}` | Drive for 0.1–60 s, then stop. `power` 0–100, `angle` −180 to 359 (0 forward, 180 reverse, 90 turns right on the spot, −90 left; 270 is *not* a left spin, the controller backs up instead). Drive steps in a row flow into each other without a stop in between. The move is repeated every 0.5 s, so the agent's motion watchdog keeps working. |
| `led` | `{"led": {"r": 2, "b": 2}}` | Status lights, any of `r`, `b` (front), `y`, `g` (back): 0 unchanged, 1 off, 2 on, 3/4 slow flash, 5/6 fast flash. |
| `cmd` | `{"cmd": {"cmd": "lcd", "s": 2, "l": 1}}` | Any controller command, sent as is through the agent's motion guard. `shut-down`, `ready`, `reset`, `update` and `upgrade` are refused. |
| `action` | `{"action": "dance"}` | Run a built-in action from the list above, in place. Always the built-in one, even if a routine replaces it. |
| `wait_until` | `{"wait_until": {"field": "arm", "eq": 1}, "timeout": 5, "else": "stop"}` | Wait for the robot's state. `field`: `battery`, `charging` (0 no, 1 charging, 2 charged), `arm`, `lightsaber`, `projector`, `lcd_s`, `lcd_l` (2 or more is on), from the controller's `gin` report (every 5 s). One comparison: `eq`, `ne`, `lt`, `le`, `gt`, `ge`. `timeout` 0.1–600 s (default 30). `else`: `stop` ends the routine when it times out (default), `continue` goes on with the next step. A field not reported yet counts as −1. |
| `repeat` | `{"repeat": 3, "steps": [...]}` | Run the inner steps 1–1000 times. Nests up to 4 deep. |
| `routine` | `{"routine": "sentry"}` | Run another stored routine's steps in place (up to 4 deep). The outer routine's timeout and `while_charging` apply. |

## Triggers
*Built, not yet tested on the robot.* Besides running it from the API, the web page, Home Assistant or
as an action, a routine can list what else starts it (up to 20 triggers):

```json
{
  "name": "good-morning",
  "triggers": [
    {"button": 4},
    {"voice": "good morning"},
    {"schedule": {"at": "07:30", "days": ["mon", "tue", "wed", "thu", "fri"]}},
    {"schedule": {"every": 60, "from": "09:00", "to": "21:00"}},
    {"event": "doorbell", "if_busy": "replace"},
    {"mqtt": {"topic": "zigbee2mqtt/front-door", "field": "contact", "value": false}}
  ],
  "steps": [...]
}
```

| Trigger | What starts the routine |
|---|---|
| `{"button": 4}` | A press of a robot button: 4 (lightsaber), 5 (arm) or 6 (patrol). The routine runs instead of the button's built-in action. Power (1), Wi-Fi (2) and pairing (3) can't be taken over, so you can always shut down, get back on the network and pair. |
| `{"voice": "good morning"}` | The phrase after "hey R2-D2", like the built-in voice commands. 2 to 6 words, letters only (write numbers as words). It can't start with or be the start of a built-in phrase ("stop", "turn left"...). Every word must be in the robot's English speech dictionary; a phrase with an unknown word is skipped and logged (`tools\r2.ps1 logcat R2Voice`). A place's phrase ("go to ...", "go home") wins over a routine's. Spotting is tuned like the other commands; longer phrases give fewer false alarms. |
| `{"schedule": {"at": "07:30"}}` | Every day at that time (24-hour clock, the robot's time zone). |
| `{"schedule": {"every": 30}}` | Every 30 minutes (5–1440), counted from midnight: 00:00, 00:30, ... |
| `{"schedule": {"every": 60, "from": "09:00", "to": "21:00"}}` | Every 60 minutes from 09:00 up to and including 21:00. A window like `22:00` to `06:00` crosses midnight. |
| `"days": ["sat", "sun"]` | In a schedule: only on these days (`sun` `mon` `tue` `wed` `thu` `fri` `sat`). For a window that crosses midnight, the day it started on. |
| `{"event": "doorbell"}` | A named event (same rules as routine names): `POST /event?name=doorbell`, `tools\r2.ps1 event doorbell`, or from Home Assistant (below). |
| `{"mqtt": {"topic": "...", ...}}` | A message on any topic of the MQTT broker R2 uses for Home Assistant (set with `tools\r2.ps1 mqtt`). See below. |

**One thing at a time.** Buttons and voice phrases replace whatever runs, like any other action.
Schedules, events and MQTT messages only start a routine when R2 is idle (nothing running, and not
patrolling, pairing or under phone control) and are skipped otherwise; add `"if_busy": "replace"` to the
trigger to cancel what runs instead. A sleeping R2 wakes up for a triggered routine. If several routines
want the same event or minute, the first by name runs. The routine's own checks still apply: it doesn't
start on the charger unless `while_charging` is set, nor below 10% battery.

**Taken buttons and phrases.** A button or phrase belongs to one routine; uploading a second routine
that wants it is refused with the owner's name. Delete the routine (or upload it without the trigger) to
give the button its built-in action back.

**Schedules need the right time.** The robot sets its clock from the network. Until the clock is set,
schedules wait, and a minute skipped by a clock jump doesn't run (so R2 doesn't run a morning of
routines at once after a long power-off). Set the time zone in Android's settings.

### From Home Assistant
With R2 on the MQTT broker, a Home Assistant automation can send an event by publishing its name to
`r2d2/<id>/event` (the `<id>` is in the topics of R2's entities, e.g. `r2d2/1a2b3c4d5e6f/battery`):

```yaml
action: mqtt.publish
data:
  topic: r2d2/1a2b3c4d5e6f/event
  payload: doorbell
```

Each stored routine also has its own button entity ("Routine greet"), so an automation can press it
directly; events let one name start whichever routine listens for it, and keep "if busy" in the routine.

An `mqtt` trigger listens to any topic, e.g. a Zigbee door sensor or another automation's topic:

| Key | Meaning |
|---|---|
| `topic` | The topic, with `+` for one level and `#` for the rest (`zigbee2mqtt/+/action`). The first level must be a plain name, so a trigger can't subscribe to everything. |
| `payload` | Optional. The whole message must be this text (spaces around it are ignored), e.g. `"ON"`. |
| `field`, `value` | Optional, instead of `payload`. The message is JSON and this top-level key has this value, e.g. `"field": "contact", "value": false`. |
| `every_message` | Optional. By default the routine starts when the message *starts* matching (the door opens), not on every repeat of the same state. `true` starts it on each matching message (a button that always sends `"single"`). |

Retained messages (the last state the broker replays when R2 connects) never start a routine; they only
tell R2 what the state was, so reconnecting doesn't run anything.

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
- [good-morning.json](routines/good-morning.json): triggers: a weekday schedule, a voice phrase and the arm button.
