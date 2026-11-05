# Sound reactions: claps and loud sounds

R2 listens with the same microphone the voice commands use and reacts to what he hears:

| He hears | He does (built in) | With `full` liveliness also |
|---|---|---|
| One clap | a curious "hm?" chirp (sound 5) | glances to the side and back |
| Two claps, 0.15–0.8 s apart | a happy song (sound 1). Also wakes him from sleep. | wiggles his head |
| A loud sound that lasts about a quarter second (a shout, a door slam, something dropped) | a startled sound (sound 12) | flinches his head |

A routine named `react-clap`, `react-double-clap` or `react-loud` replaces the built-in reaction
(see [routines.md](routines.md)). You can also run a reaction by hand to hear it:
`tools\r2.ps1 action react-clap`.

## When he reacts

The shared **liveliness** setting (off, quiet, chatty, full), which the idle personality also uses,
decides how much he reacts:

- **off:** no reactions, and the microphone isn't kept open for them.
- **quiet:** only very loud sounds (peak at least -6 dBFS), sound only.
- **chatty:** claps, double claps and loud sounds, sound only.
- **full:** the same, with head moves.

On top of that, the **Sound reactions** switch turns just this feature on or off (default on): on the
web page under Settings, as a Home Assistant switch, or with `tools\r2.ps1 action reactions-off` /
`reactions-on`. The setting is kept across reboots.

He doesn't react:
- while he is asleep, except to a double clap, which wakes him;
- while he is patrolling, following someone, pairing, under phone control, or running an action or routine;
- while he is playing a sound or driving, and for 0.7 s after, so he doesn't react to himself;
- while he is listening for a voice command or talking with Home Assistant;
- during projector clips and music (the microphone is closed then anyway);
- while watch-with-me follows a film (the cue file does the reacting then);
- more than once every 5 seconds.

Sound reactions keep working when voice commands are off, or when the speech model can't load.

## How it hears

`SoundReactions.java` cuts the audio into 20 ms frames and keeps a noise floor that follows the room:
it drops quickly when the room gets quieter and rises over a few seconds when it gets louder (a TV,
a fan). A sound that keeps going for 3 s becomes the new floor. A sound event starts when a frame's
peak is 20 dB above the floor (and at least -35 dBFS):

- **clap:** the event is at most 160 ms long and loudest at its start;
- **double clap:** two claps 150–800 ms apart (a single clap is reported once that window has passed,
  so there is up to a second of delay);
- **loud:** frames averaging -15 dBFS or more for 240 ms in one event.

All of these numbers are first guesses for the robot's microphone. Every sound he hears, reacted to
or not, leaves a line like `heard clap (-12 dB, floor -52 dB)` in the Android log:
`tools\r2.ps1 logcat heard`. R2 Main's own status screen also shows the noise floor and the last
thing heard. Tune the constants at the top of `SoundReactions.java` from those.

Off-robot tests with made-up audio: `apps/test.sh`.
