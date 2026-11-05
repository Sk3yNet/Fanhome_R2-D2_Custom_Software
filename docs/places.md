# Rooms: sending R2 to places in the house

"Hey R2-D2, go to the kitchen" sends R2 to a room you taught him. *Built, not yet tested on the robot.*

R2 has no map, no wheel sensors and (as far as the main board can tell) no obstacle sensors, so he
can't find his own way. Instead, you drive him once from his **home spot** to a room with the web
page's joystick while he records the drive. Going there later replays that drive. It works best on
hard floors and short routes, and the routes drift a little each time, so plan for that.

## Safety first
- **R2 can't see stairs or obstacles.** Teach routes well away from stairs and drops, and keep them clear.
  Block stairs physically if a route comes close.
- Every trip starts with a chirp and a 1.5 s pause, so people and pets get a moment.
- A trip is a routine underneath, so all the routine safety rules apply: it stops on "stop" (voice, web
  page, Home Assistant, the robot's buttons, `POST /stop`), on a controller error, on the charger, below
  10% battery, and after its drive time plus a quarter. The agent's motion watchdog still applies.
- Routes may use drive power up to 60. While you teach, the web page caps the joystick at 40.

## Home and where R2 is
Every route starts at **home**: pick one spot (for example next to the charger, facing a wall) and always
put R2 there, facing the same way, before teaching. R2 keeps track of where he is: home, a room, or
unknown. He becomes unknown when a trip is stopped part way, or when anyone drives or turns him some
other way (joystick, phone app, turn actions, routines that move). Then he refuses to go anywhere until
you put him back home and say so (`here home`, the web page's **R2 is home now**).

The controller ignores driving while R2 charges, so home can't be *on* the charger. If you unplug him
there anyway, `POST /places/settings?charger_is_home=1` makes plugging in mean "R2 is home".

## Teaching a room
On the web page (`http://<robot>:7700/`, **Rooms**):
1. Put R2 at home. Type the room's name (`kitchen`, `living room`) and tap **Teach the way there**.
2. Drive him to the room with the joystick. Smooth, steady moves give the best replays.
3. Tap **Save route**. R2 is now in that room.
4. Optional: tap **Teach the way back** and drive him home, then **Save route**.

If a room has no taught way back, R2 reverses the way there: he turns around, drives the route
backwards with left and right swapped, and turns around again so he faces the way he started. For that
he needs a taught **turn-around**: tap **Teach a turn-around**, spin him half a turn on the spot, and save.

Re-teaching the way there drops a taught way back, because it no longer matches.

From a PC: `tools\r2.ps1 teach kitchen`, drive, `tools\r2.ps1 teach-save` (also `teach-back`, `teach-turn`,
`teach-cancel`). Joystick drives from the phone app are recorded too.

## Sending R2
- **Voice on the robot:** "hey R2-D2, go to the kitchen", "go home" or "come home". The phrases come from each
  room's spoken names (default: "the" plus the name). A word the speech model doesn't know is skipped (see the log).
- **Home Assistant (MQTT):** a **Go to** select (pick a room; its state shows where R2 is), a **Location**
  sensor, and **Go home** and **Tour the rooms** buttons.
- **Home Assistant voice:** see [home-assistant-voice.md](home-assistant-voice.md#rooms).
- **API and PC tool:** `POST /goto?place=kitchen` (`tools\r2.ps1 goto kitchen`), `POST /goto?place=home`.
- **Actions:** `go-home` and `tour` are actions, and `goto:<place>` works as an action name too
  (`POST /action?name=goto:kitchen`).

From another room R2 goes via home: kitchen to office is kitchen, home, office.

## Smarter patrol: the tour
`tour` (`POST /tour`, the web page, Home Assistant) visits every room in the order they were taught, stands
still for 3 s in each, and comes home. If a room has no way back, the tour ends there.
`POST /places/settings?patrol_tour=1` makes the patrol button and the "patrol" voice command tour the rooms
instead of the factory's 60 s wander (when R2 knows where he is).

Obstacle avoidance waits for the sensor test below.

## Sensors
The robot has IR, ultrasonic and foot-drop sensors, but no known controller message reports them; the
controller probably handles them itself. The agent now logs every change of the `gin` fields we don't
understand (`status`, `error`, and any new field), marked "(while driving)" when R2 was moving. To find
out what the sensors do, drive R2 slowly toward a wall, and lift a foot, then read `tools\r2.ps1 log`.

## API
| Route | What |
|---|---|
| `GET /places` | `{places:[{name, spoken, there_seconds, there_segments, way_back, area?}], location, teaching, turn_around_seconds, charger_is_home, patrol_tour, running, last_trip}` |
| `GET /place?name=` | one room as JSON (below) |
| `POST /place` | upload a room (JSON body; replaces one with the same name) |
| `POST /place/delete?name=` | delete a room |
| `POST /goto?place=` | go to a room, or `home` |
| `POST /tour` | the tour |
| `POST /place/here?name=` | where R2 is now: `home`, a room, or `unknown` |
| `POST /place/teach?name=&what=there\|back\|turn` | start teaching (`&spoken=the kitchen,cooking room&area=Kitchen` for a new room) |
| `POST /place/teach/save`, `POST /place/teach/cancel` | end teaching |
| `POST /places/settings?charger_is_home=1\|0&patrol_tour=1\|0` | settings |

`GET /status` has `location` (and `teaching` while teaching).

## Format
Rooms are kept in R2 Main's `files/places.json`. One room:

```json
{
  "name": "kitchen",
  "spoken": ["the kitchen", "the cooking room"],
  "area": "Kitchen",
  "there": [{"power": 30, "angle": 0, "seconds": 4.2}, {"power": 24, "angle": 90, "seconds": 1.1}],
  "back": [{"power": 30, "angle": 180, "seconds": 4.0}]
}
```

- `name`: a-z, 0-9 and -, up to 32 characters; not `home` or `unknown`.
- `spoken`: up to 5 phrases of lowercase words; R2 listens for "go to" plus each. Two rooms can't share one.
- `area`: optional, e.g. the Home Assistant area's name.
- `there`, `back`: drive segments, the controller's `move` held for `seconds` (0.05-60): `power` 0-60, `angle`
  -180 to 180 (0 forward, 90 spin right, -90 spin left, 180 back; in between, the controller curves).
  Up to 400 segments and 300 s per route. `back` is optional.
- `marker`: reserved for printed markers (a later phase).

A whole trip (including going via home) is limited to 700 s of driving.
