# Watch with me

R2 watches a film with you: he gasps, cheers, looks around and whistles at the right moments. The
moments come from a **cue file** for that film, a list of times and reactions. R2 follows the film's
position through Home Assistant (any media player that reports a position), or you start him by hand
from the PC tool.

*Built, not yet tested on the robot.* The cue format, the position tracking and the stationary
reactions are covered by off-robot tests (`apps/test.sh`); nothing here has run on real hardware yet.

R2 never drives during a film. Reactions are sounds, head turns, lights, the displays, the arm and the
other actions that keep him in place. They also run while he sits on the charger (the controller may
ignore head moves there; sounds and lights still work).

## Quick test with any video

```
tools\r2.ps1 routine-put docs\watch-with-me\gasp.json
tools\r2.ps1 routine-put docs\watch-with-me\cheer.json
tools\r2.ps1 routine-put docs\watch-with-me\sad.json
tools\r2.ps1 cue-put docs\watch-with-me\test-clip.json
tools\r2.ps1 watch-start test-clip
```

Start any video at the same moment. R2 reacts at 0:05 and then every 15 seconds for two minutes.
`tools\r2.ps1 watch` shows where he thinks the film is and what comes next; `watch-pause`,
`watch-resume` (optionally `-At 1:02`), and `watch-stop` do what they say. `-At` also works with
`watch-start` to join a film in the middle: `tools\r2.ps1 watch-start a-new-hope -At 1:02:03`.

## Cue files

```json
{
  "name": "a-new-hope",
  "title": "Star Wars: Episode IV - A New Hope",
  "description": "Optional text for listings",
  "match": ["a new hope", "episode iv"],
  "offset": 0,
  "cues": [
    {"at": "0:02:10", "routine": "gasp", "note": "the ship is boarded"},
    {"at": 1234.5, "steps": [{"sound": 1}, {"head": -40}, {"wait": 1}, {"head": 0}]},
    {"at": "2:00:30", "routine": "cheer", "timeout": 20}
  ]
}
```

| Key | Meaning |
|---|---|
| `name` | 1–32 characters: `a-z`, `0-9` and `-`, starting with a letter or digit. |
| `title`, `description` | Optional text for listings. |
| `match` | Optional. Pieces of the media title (case doesn't matter, at least 3 characters each) that pick this file when Home Assistant reports what's playing. With none, the file is only used when named. |
| `offset` | Optional. Seconds added to every cue, −600 to 600. Use it when your copy is a different cut, has a studio logo the file's author didn't, or the player reports its position late. |
| `cues` | 1–2000 cues, in any order. |

Each cue has:

| Key | Meaning |
|---|---|
| `at` | When, in seconds (`1234.5`) or as a time (`"1:02:03"`, `"2:03"`, `"2:03.5"`), from the start of the film. |
| `routine` | A stored routine to run ([routines.md](routines.md)), or... |
| `steps` | ...routine steps right here (same step types as a routine). Steps that drive (`drive`, the turning modes, `move` commands, and the `turn-*`, `forward`, `circle` and `dance` actions) are refused. |
| `timeout` | Optional, for `steps`: seconds the reaction may take, 1–120 (default 30). |
| `note` | Optional. What happens in the film, shown in `tools\r2.ps1 watch` and Home Assistant. |

A stored routine used as a reaction runs *stationary*: any step in it that would drive is skipped. Keep
reactions short (a few seconds), so they're over before the next one.

Writing the cue files is the real work. Watch the film with a remote in hand, note the times, and keep the
file with your other routines. Cue files are only times and R2's reactions, so you can share them
without sharing any of the film.

### Using cue files

| Do | PC tool | API (port 7700, signed, or a plain control token: docs/security.md) |
|---|---|---|
| List cue files and the session | `tools\r2.ps1 cues` | `GET /cues` |
| Show one | `tools\r2.ps1 cue a-new-hope` | `GET /cue?name=a-new-hope` |
| Upload (or replace) | `tools\r2.ps1 cue-put my-film.json` | `POST /cue` with the JSON as the body |
| Delete | `tools\r2.ps1 cue-delete a-new-hope` | `POST /cue/delete?name=a-new-hope` |
| What's R2 watching | `tools\r2.ps1 watch` | `GET /watch` |
| Start, pause, resume, stop | `watch-start <cue> [-At t]`, `watch-pause`, `watch-resume [-At t]`, `watch-stop` | `POST /watch` with `{"state": "playing", "cue": "a-new-hope", "position": "1:02:03"}`; `state` `paused`, or `stopped` |
| Switch reactions off / on | `tools\r2.ps1 watch-off`, `watch-on` | `POST /watch` with `{"enabled": false}` |

Uploading checks the whole file, like routines, and tells you which routines it uses that aren't on the
robot yet. Cue files are kept in R2 Main's data (`files/cues/<name>.json`).

## Following Home Assistant

R2 doesn't watch the screen or listen to the film; he follows the position that your media player
reports to Home Assistant. Most players do: Plex, Kodi, Jellyfin, Emby, Apple TV, Chromecast with most
apps. Some TV apps don't report a position, and those can't drive R2 (use `watch-start` by hand instead).
Check in Home Assistant's **Developer tools → States**: your player needs a `media_position` attribute while
it plays.

R2 must be connected to Home Assistant over MQTT ([architecture.md](architecture.md#home-assistant-over-mqtt-r2-main)).
Then add this automation (Settings → Automations → Create → Edit in YAML), with your player's entity and
the topic that `tools\r2.ps1 watch` shows (`r2d2/<robot id>/watch/set`):

```yaml
alias: R2 watches with me
mode: queued
max: 5
triggers:
  - trigger: state
    entity_id: media_player.living_room_tv
  - trigger: time_pattern
    seconds: "/30"
conditions:
  - condition: template
    value_template: "{{ trigger.platform == 'state' or is_state('media_player.living_room_tv', 'playing') }}"
actions:
  - action: mqtt.publish
    data:
      topic: r2d2/0123456789ab/watch/set
      payload: >-
        {% set p = 'media_player.living_room_tv' %}
        {% set pos = state_attr(p, 'media_position') %}
        {% set at = state_attr(p, 'media_position_updated_at') %}
        {% if pos is number and at and is_state(p, 'playing') %}
          {% set pos = pos + as_timestamp(now()) - as_timestamp(at) %}
        {% endif %}
        {{ {'state': states(p), 'title': state_attr(p, 'media_title'),
            'position': pos if pos is number else none} | tojson }}
```

When something plays, the title picks the cue file (its `match` list). If no file matches, R2 does
nothing; `tools\r2.ps1 watch` then says `no cue file` with the title Home Assistant sent, which is the
text to put in `match`. To pick a file regardless of the title, add `'cue': 'a-new-hope'` to the payload.

How R2 keeps in step:
- Between updates he counts on from the last position. The automation sends one on every change (play,
  pause, seek) and every 30 s while playing, to correct drift.
- A jump of more than 4 s (you skipped ahead or back) is a seek: the cues in between are skipped, and
  after skipping back they play again. Smaller corrections never replay a cue.
- A cue noticed more than 3 s late is skipped rather than played late.
- With no update from Home Assistant for 90 s while playing, R2 assumes he lost track and goes quiet
  (`lost sync`) until the next update.
- Paused, idle or off: nothing fires. A different film (a title no longer matching) ends the session.

In Home Assistant R2 gets a **Watch with me** switch (reactions on or off, kept across reboots) and a
**Watch-with-me state** sensor (`off`, `playing`, `paused`, `no cue file`, `lost sync`) with the cue
file, title, last and next reaction as attributes. The film's position isn't published (the player has
it), so the sensor doesn't change every second.

## What can and can't interrupt what

- A reaction doesn't interrupt anything else you started: an action, a routine, patrol, pairing or
  phone-app control. That cue is skipped.
- Anything you start interrupts a running reaction, as with routines.
- The **stop** action (emergency stop, voice "stop", the phone app's stop) also ends the watch session;
  start it again with `watch-start`, or Home Assistant restarts it with its next update.
- While a film plays with a cue file, R2 stays awake instead of falling asleep after 3 idle minutes.
- The routine safety rules apply to every reaction: timeout, controller error, low battery and stop
  ([routines.md](routines.md#safety)). The charger rule doesn't: reactions run on the dock.

## Examples
- [test-clip.json](watch-with-me/test-clip.json): reactions every 15 s for trying it with any video.
- [gasp.json](watch-with-me/gasp.json), [cheer.json](watch-with-me/cheer.json), [sad.json](watch-with-me/sad.json):
  reaction routines that the test clip uses. Upload them with `routine-put`.

The sound numbers in the examples are the factory sounds as `play_sound` numbers them (12 the "huh",
1 the singing, 3 the failed-pairing sound, 8 the wake-up). They'll fit better once the sounds are tagged by mood.
