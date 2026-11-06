# Soundboard

The web page's second tab, **Soundboard**, has everything R2 can play on demand: his reactions, newly
generated beeps, a sentence said in beeps, random sounds by mood, and every factory sound by number.
Each one is also a routine step, so anything on the board can go into a routine.
*Built, not yet tested on the robot.*

Open `http://<robot>:7700/#sounds`, or tap **Soundboard** under STOP. STOP stays at the top of both tabs.
Tapping a button plays it and shows its routine step (for example `{"sound": 8}`) with a **Copy** button,
to paste into a routine file. **Stop sound** silences whatever is playing.

## What's on it

| Section | Plays | Routine step | API |
|---|---|---|---|
| Reactions | The built-in actions that are mostly a sound: the clap, double-clap and loud-sound reactions, wolf whistle, freak out, make some noise, who are you, huh?, shake head. One you replaced with your own routine says *your routine*, and plays yours. | `{"action": "react-clap"}` | `POST /action?name=react-clap` |
| New beeps | A newly generated chirp in a mood, different every time ([chirps.md](chirps.md)). | `{"chirp": "happy"}` | `POST /chirp?mood=happy` |
| Say it in beeps | Droid sounds shaped like a sentence you type (up to 500 characters in a routine). | `{"beeps": "Hello there!"}` | `POST /chirp` with the text as the body |
| Moods | A random factory sound from a mood bank, never the same one twice in a row ([liveliness.md](liveliness.md#mood-banks)). `random` picks from all banks. | `{"sound": "happy"}` | `POST /sound?mood=happy` |
| Factory sounds | One factory sound by number, with its name where its use is known. | `{"sound": 8}` | `POST /sound?number=8` |

Reactions only lists what this build has, so new ones show up by themselves. The mood banks are the same
ones the idle chatter uses: the defaults, or your `apps/main/assets/moods.json`. The factory sounds are the
ones in `apps/main/assets/sounds.json` (not in the repository); without it the board still has the
reactions and the generated beeps.

The board plays even when the Activity is *Off*, because you asked for it. Mute silences it, like every
other sound, and the page says so. A sound wakes a sleeping R2.

## API (port 7700, signed requests: [security.md](security.md))

| Route | Level | Does |
|---|---|---|
| `GET /sounds` | read | The board as JSON (below). |
| `POST /sound?number=N` | control | Plays factory sound N (0–255). 400 if this build has no such sound or R2 is muted. |
| `POST /sound?mood=<bank>` | control | Plays a random sound from that mood bank, or from all of them with `random`. |
| `POST /sound/stop` | control | Stops the sound playing (a factory sound, a chirp, or a projector clip's audio). |

`GET /sounds` answers:
```json
{
  "volume": 70, "muted": false, "status": "14 sounds, volume 70%, generated beeps on",
  "sounds": [{"number": 8, "label": "Wake up", "moods": ["happy"], "step": {"sound": 8}}],
  "moods": [{"name": "happy", "sounds": [8, 9, 13], "step": {"sound": "happy"}},
            {"name": "random", "sounds": [], "step": {"sound": "random"}}],
  "chirps": [{"mood": "random", "step": {"chirp": "random"}}],
  "reactions": [{"action": "react-clap", "label": "Hm? (a clap)", "custom": false, "step": {"action": "react-clap"}}],
  "beeps": {"max": 500, "step": {"beeps": "Hello there!"}}
}
```

## PC tool

```
tools\r2.ps1 sounds            # the board: factory sounds with names, mood banks, chirps, reactions
tools\r2.ps1 sound 8           # factory sound 8
tools\r2.ps1 sound happy       # a random happy one
tools\r2.ps1 sound stop
tools\r2.ps1 chirp excited     # a new chirp; chirp 'Hello there' says it in beeps
tools\r2.ps1 action whistle    # a reaction
```
`python3 tools/r2.py` takes the same commands.

## Test on the robot
- Every factory sound button plays the sound its label says (the names come from what the factory
  actions use them for; a wrong one is a one-line fix in `Soundboard.KNOWN_SOUNDS`).
- Mood buttons play a different sound each tap; `random` covers all banks.
- **Stop sound** cuts off a long one (100 and 101, the projector clips' audio).
- Muted: the factory sound and mood buttons say R2 is muted.
- A routine with `{"sound": "happy"}` and `{"beeps": "Hello there!", "until_done": true}` steps plays them in order.
