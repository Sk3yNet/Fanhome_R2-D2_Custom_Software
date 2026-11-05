# Drive lock (enable before driving)

With the drive lock on, R2's wheels stay locked until someone enables driving, like pressing START on a
gamepad-driven droid. It guards against a stray Home Assistant automation, a routine started by mistake,
a phone in a pocket or a script on the wrong robot driving R2 off a table.

It is **off by default**, so R2 drives the way the factory software does. Switch it on once and it stays
on across reboots:

- web page: Settings > **Require enabling to drive**
- `tools\r2.ps1 drive-lock on` (`off` to switch it off again)
- Home Assistant: the **Require enabling to drive** switch (a configuration entity)
- `POST /drive?lock=1`

## Enabling driving
- web page: **Enable driving** above the joystick (the joystick is greyed out while driving is locked)
- `tools\r2.ps1 drive-enable [minutes]`
- Home Assistant: the **Driving enabled** switch
- `POST /drive?enable=1&minutes=N`

Driving stays enabled until R2 hasn't driven for 10 minutes (or the `minutes` you chose, 1 to 120).
Driving resets the timer, so a long patrol or a joystick session doesn't run out halfway.

Driving locks again when:
- the idle time runs out,
- someone locks it (**Lock driving** on the web page, `tools\r2.ps1 drive-disable`, the Home Assistant
  switch, `POST /drive?enable=0`),
- the **emergency stop** runs (STOP on the web page, `tools\r2.ps1 stop`, `POST /stop`),
- the low-battery stop fires,
- R2 restarts: enabling is never stored.

If R2 is driving when it locks, the agent stops it at once.

## What is locked
Only the wheels: `move` with power, and the controller's own movement modes (turn left / right / around,
forward, circle, patrol, and dance, which uses the turns). These stay allowed while locked:
- stop commands, always;
- head moves (face tracking and many actions use them; the motion guard caps them anyway);
- sounds, lights, the lightsaber, the arm, the displays and the projector.

## Where it is enforced
In the agent's motion guard (`MotionGuard`, `DriveLock`), on every command headed for the controller,
whoever sent it: R2 Main, the official phone app, Home Assistant, plugins ([plugins.md](plugins.md)), routines, `POST /serial`, the gamepad, and any future
feature such as follow-me. R2 Main can't get around it.

R2 Main also knows the state (the agent tells it on every change and every 5 s), so it can react
sensibly instead of having commands silently dropped:
- a driving action (voice, buttons, phone app, Home Assistant, the web page) is refused with the "didn't
  understand" sound;
- a routine that drives (a `drive` step, a driving `mode` or `cmd`, a driving `action`, also inside
  `repeat` or a called routine) doesn't start: its status says `stopped, driving is locked`, nothing of it
  runs, and R2 makes the same sound;
- if driving locks while a routine or a driving action runs, it ends at its next drive step (the agent
  has already stopped the wheels);
- phone app driving is refused, with the sound at most every 2 s.
- "follow me" ([follow-me.md](follow-me.md)) doesn't start, and ends if driving locks while R2 follows;
- a trip to a place or a tour ([places.md](places.md)) doesn't start, so R2 still knows where he is;
  teaching a route needs driving enabled, since it records the joystick.

`POST /serial` answers `409` with the reason for a refused drive command.

## For code that drives
New features that move R2 (follow-me, room navigation, a gamepad) need no extra code to be safe: the agent
refuses their drive commands while locked. To behave well:
- check `robot.driveAllowed()` in R2 Main before starting (follow me and room trips do), and say no (the "didn't understand" sound
  via `driveRefused(...)` in `Brain`) instead of starting something that can't move;
- treat it turning false as "stop now" and end the behavior;
- a gamepad can map its START button to `POST /drive?enable=1` (or `{"agent":"drive-set","enable":true}`
  from R2 Main), which is exactly what the lock is modelled on.

## API
| Route | What it does |
|---|---|
| `GET /drive` | `{"lock", "enabled", "allowed", "seconds_left", "idle_minutes", "state"}`; `state` is `off`, `locked` or `enabled` |
| `POST /drive?lock=1\|0` | switch the lock on or off (kept across reboots); switching it on locks driving and stops R2 |
| `POST /drive?enable=1[&minutes=N]` | enable driving until idle for N minutes (default 10, 1–120) |
| `POST /drive?enable=0` | lock driving again (stops R2 if it is driving) |

`GET /status` includes the same object as `drive`. Over the bridge the agent sends R2 Main
`{"agent":"drive",...}` (the same fields) and `{"agent":"drive-refused"}`; R2 Main asks for changes with
`{"agent":"drive-set","enable":true|false,"minutes":N,"lock":true|false}`.
