# Generated chirps

The factory software plays the same recorded confirmation beep after nearly every command. R2 Main
instead makes up a new R2-style chirp each time (`Chirp.java`). It's pure Java and synthesizes the
audio on the robot, so there are no sound files to ship. *Built, not yet tested on the robot.*

A chirp is a short phrase of tones: blips, pitch glides, warbles (fast vibrato) and trills (fast
switching between two pitches), with short gaps between them. A **mood** picks the recipe and a random
**seed** fills in the details, so the same mood sounds a bit different every time.

| Mood | Sounds like |
|---|---|
| `ack` | The confirmation beep: two or three quick blips, often ending on a short rise (about 0.1–0.4 s). |
| `happy` | Bright, mostly rising, with a trill or two. |
| `excited` | Fast, high blips and trills with hardly a gap. |
| `question` | A little chatter, then a long rise. |
| `sad` | One or two long, low, falling whistles with a slow wobble. |
| `worried` | Warbles that keep falling, with a nervous blip in between. |
| `chatter` | R2 "talking": a mix of everything. |
| `random` | Any of the above except `ack`. |

Two set pieces work like moods but are never picked by `random`:

| Name | Sounds like |
|---|---|
| `whistle` | A wolf whistle: a quick rise, a breath, then up again and a long fall (about 0.9 s). The pitch shifts a little each time. |
| `scream` | R2's freak-out: frantic trills, a long shrieking fall with a fast wide vibrato, panicky blips, and a second shriek (about 2–3 s). |

The `whistle` and `freak-out` actions use them ([below](#wolf-whistle-and-freak-out)).

## Using them
- **Confirmation beep:** on by default. `tools\r2.ps1 action chirps-off` brings back the factory
  sound 7, and `chirps-on` switches back. The web page has a *New beeps each time* switch. The setting
  survives reboots. If the generated audio can't play, R2 falls back to sound 7.
- **Action:** `chirp` plays one in a random mood (`tools\r2.ps1 action chirp`, the web page, or the
  *Chirp* button in Home Assistant).
- **PC tool:** `tools\r2.ps1 chirp` (random), `tools\r2.ps1 chirp sad`, or `tools\r2.ps1 chirp 'Is the garage door open?'`.
- **Routines:** `{"chirp": "happy"}` or `{"chirp": "question", "until_done": true}` (see [routines.md](routines.md)).
- **API:** `POST /chirp?mood=<mood>`, optionally `&seed=<n>` to repeat one exactly. To shape a chirp
  like a sentence, send the text as the body (or `?text=`, up to 2000 characters), optionally with a `mood`.

Chirps play on the music stream, so the volume and mute settings apply, and they interrupt whatever
sound is playing. The microphone ignores them like any other droid sound.

## Wolf whistle and freak-out
Ask R2 a "how pretty" or "how scary" question and he answers it his way. *Built, not yet tested on the robot.*

| Say "hey R2-D2", then | Action | What R2 does |
|---|---|---|
| "how pretty is ...?" / "how pretty are ...?" | `whistle` | Looks over to his right, wolf whistles, and looks back. |
| "how scary is ...?" / "how scary are ...?" | `freak-out` | Screams and shakes his head wildly from side to side. |

Only the start of the question counts, so "how pretty is the sunset" and "how scary are spiders" both work, whatever
follows. Neither action drives the wheels, so they work with the [drive lock](drive-lock.md) on. If the generated
sound can't play, R2 uses a factory sound instead (his song for the whistle, the "huh?" for the freak-out).

The actions also run from `tools\r2.ps1 action whistle` / `action freak-out`, the web page, the **Wolf whistle** and
**Freak out** buttons in Home Assistant, and routine steps (`{"action": "whistle"}`). A routine named `whistle` or
`freak-out` replaces the built-in one. For Home Assistant's voice pipeline, see
[home-assistant-voice.md](home-assistant-voice.md#letting-voice-and-the-ai-run-r2s-actions).

## Droid sounds shaped like text
For a beeps-only reply, `Chirp.speak(text, mood, seed)` turns a sentence into droid sounds of about the
right length and shape:
- one to three tones per word, roughly by its syllables;
- short pauses between words, longer ones after commas, longer still between sentences;
- the ending follows the punctuation: a long rise for `?`, a trill for `!`, and a fall for a statement;
- with no mood given, `?` picks `question`, `!` picks `excited`, and anything else `chatter`. A mood
  given with the text sets the pitch range (e.g. `sad` is lower);
- capped at 8 seconds: in long text, words are skipped evenly.

Inside R2 Main, call `Brain.chirp(mood, text, seed)` (seed `null` for a new one each time), or
`Sounds.speak(text, mood)`. From outside, use `POST /chirp` with the text as the body.

## Listening without the robot
`apps/test.sh` checks every mood over many seeds (length, pitch range, no clipping, no clicks at the
ends, variety) and writes example WAV files to `apps/out/chirps/`. CI uploads them as the
`example-chirps` artifact of each build, so you can listen in a browser.

## Tests to do on the robot
- The confirmation beep (e.g. `action shake-head`, `stop`, `unmute`) is a new chirp each time, at the
  set volume, and silent when muted.
- `chirps-off` brings back the factory beep, `chirps-on` the chirps, and the setting survives a reboot.
- Every mood with `tools\r2.ps1 chirp <mood>` sounds like R2 and isn't harsh on the robot's speaker
  (there's no low end, by design).
- A long `tools\r2.ps1 chirp '<sentence>'` plays to the end, and `stop` cuts it off.
- A routine with `{"chirp": "question", "until_done": true}` waits for the chirp before the next step.
- "hey R2-D2" (Assist) still chirps when it starts listening, and R2's own chirp doesn't trigger the mic.
