# Liveliness and idle personality

**Liveliness** is one setting for how much R2 does on his own. Features that make R2 act without being
asked read it, so one switch turns them all up or down.

| Level | Idle chatter (this page) | Other features |
|-------|--------------------------|----------------|
| `off` | none | [sound reactions](sound-reactions.md) off |
| `quiet` (default) | a sound every 1 to 2 minutes, no head moves | reactions to very loud sounds only, no head move |
| `chatty` | something every 25 to 50 s; about a third of the time he looks around instead | reactions to claps, double claps and loud sounds, no head move |
| `full` | something every 10 to 25 s; half the time he looks around, sometimes with a sound | normal reactions, with head moves |

The setting is kept across reboots. Sound reactions also have their own on/off switch
(`reactions-on` / `reactions-off`), see [sound-reactions.md](sound-reactions.md).

## Setting it

- `tools\r2.ps1 liveliness chatty` (or `python3 tools/r2.py liveliness chatty`); with no level it shows
  the current one.
- As actions: `liveliness-off`, `liveliness-quiet`, `liveliness-chatty`, `liveliness-full`, so
  `POST /action?name=liveliness-full` works, and routines' reserved-name rules apply to them.
- `GET /status` shows it as `liveliness`.

In R2 Main, other features read it with `Brain.liveliness()` (one of `IdlePersonality.LEVELS`) and set it
with `Brain.setLiveliness(level)`. Unknown values fall back to `quiet`.

## Idle chatter

While R2 is awake and idle, he plays a random droid sound from a mood bank now and then and, at
`chatty` and `full`, sometimes turns his head to one side for a second or two and then faces forward
again. Nothing happens while:

- he is asleep, patrolling, pairing or under phone control,
- an action or routine runs, or he is being driven or his head was moved by hand in the last 5 s,
- music plays, a voice conversation is going on, or he is listening after "hey R2-D2",
- a face is being tracked (face tracking moves the head itself),
- another sound is playing (chatter waits for it).

After any of these, the wait starts again from the beginning, so R2 doesn't chatter straight after a
command. Chatter never counts as activity: R2 still falls asleep after the usual idle time. Mute
silences it like every other sound.

## Mood banks

Moods are groups of sound numbers (the numbers in `assets/sounds.json`). Without a mood file R2 uses
the factory sounds whose use is known:

```json
{"chatty": [1, 5, 6], "happy": [8, 9, 13], "curious": [12]}
```

To use your own, put `apps/main/assets/moods.json` next to `sounds.json` (also kept out of git) and
rebuild, for example:

```json
{
  "chatty":  [1, 5, 6, 20, 21],
  "happy":   [8, 9, 13, 30],
  "sad":     [40, 41],
  "whistle": [50, 51],
  "curious": [12]
}
```

Each idle sound picks a random mood, then a random sound in it, never the same sound twice in a row
when there is a choice. Numbers the build can't play are skipped. R2 Main's status screen shows the
moods in use and the last idle event.
