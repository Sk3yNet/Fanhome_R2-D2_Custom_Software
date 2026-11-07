# Noisy room and TV mode

Music or a TV near R2 can sound a lot like people talking to him. The drum hits look like claps, and
now and then a bit of a song matches "hey R2-D2" or a command. On 2026-11-06 a TV playing music made him
hear more than 100 claps in three minutes, then "hey R2-D2", "who are you" and "patrol", and he drove
off on patrol.

To stop that, R2 becomes **careful** while the room is noisy, or while you switch **TV mode** on.

## What careful means

| | Normal | Careful |
|---|---|---|
| Claps and loud sounds ([sound-reactions.md](sound-reactions.md)) | he reacts | he ignores them (a double clap doesn't wake him either) |
| "Hey R2-D2" | normal threshold (`1e-34`) | much stricter (`1e-24`), so music rarely wakes him |
| Voice commands that move him (turn, forward, circle, dance, patrol, follow me, go to a room, a routine that drives) | run at once | he only answers "hm?" the first time; say the same command again within 8 seconds and it runs |
| Voice commands that don't move him ("who are you", "stop", ...) | run at once | run at once |

"Stop" always works at once, and forgets a command he was waiting to hear again.

Only the on-robot voice commands are affected. The web page, Home Assistant, the phone app, buttons,
schedules and `r2.ps1 action` run things as before, and the [drive lock](drive-lock.md) still applies
on top of all of this.

## When the room counts as noisy

R2 decides this by himself from the same microphone audio the sound reactions use
(`SoundReactions.java`):

- **10 or more sound events within 20 seconds.** People rarely clap or bang that often, but every beat
  of a song is an event. Only sounds peaking at -20 dBFS or louder count, so quiet clicks (a hard
  drive working) don't make the room noisy.
- **or the room itself is loud:** its noise floor is -40 dBFS or louder (a TV turned up).

He stays careful for **60 seconds** after the last sign of it, so a quiet bit in a song doesn't end it.
Sounds R2 makes himself (beeps, driving) don't count.

These numbers are first guesses for the robot's microphone. Tune them with the constants
`NOISY_EVENTS`, `NOISY_WINDOW_MS`, `NOISY_FLOOR_DB` and `NOISY_HOLD_MS` at the top of
`SoundReactions.java`, and `WAKE_CAREFUL_THRESHOLD` in `Voice.java`. If you replaced the phrase list with
your own `files/voice/keywords.txt`, the stricter wake threshold isn't applied: your file's thresholds are.

## TV mode

TV mode makes R2 careful all the time, even while the room sounds quiet (a film with quiet dialogue,
for instance). It's kept across reboots.

- **Web page:** the **TV mode** switch under Settings. While he is careful by himself it says
  "hearing music or a TV: careful anyway".
- **Home Assistant:** the **TV mode** switch, and a **Noisy room** binary sensor that is on while he
  hears music or a TV. You can switch TV mode from an automation, for example while the living room TV
  is playing.
- **PC:** `tools\r2.ps1 action tv-mode-on` / `tv-mode-off` (or `tools/r2.py action ...`).
- **API:** `POST /action?name=tv-mode-on`. `GET /status` has `tv_mode` and `noisy_room`.

## Checking it

R2 Main's status screen and the Android log show what he decided:

```
tools\r2.ps1 logcat heard      # every clap or loud sound he heard, reacted to or not
tools\r2.ps1 logcat careful    # when careful mode starts and ends, and commands waiting to be said again
```

The status screen's sound line shows `(noisy room)` after the noise floor, and a line like
`TV mode: off, room: noisy, waiting for "patrol" again`.

Off-robot tests with made-up audio and commands: `apps/test.sh` (`SoundReactionsTest`, `VoiceGuardTest`).
