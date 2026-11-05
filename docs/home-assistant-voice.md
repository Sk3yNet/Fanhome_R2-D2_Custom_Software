# R2 as a Home Assistant voice assistant

*Built, not yet tested on the robot.*

R2 Main can act as a voice satellite for Home Assistant's Assist. Home Assistant connects to R2 with its
own Wyoming protocol, the same way it connects to its own voice satellites. After R2's wake word, what you
say goes to the Assist pipeline you picked in Home Assistant. That can be Home Assistant's own
conversation agent, or a local or cloud AI model. R2 plays the spoken answer through his speaker.
Home Assistant announcements (`assist_satellite.announce`) also play through R2.

Off by default. When it's off, or when Home Assistant isn't connected, "hey R2-D2" and the on-robot voice
commands work as before.

## Modes
| `tools\r2.ps1 assist ...` | Wake word | What leaves the robot |
|---|---|---|
| `off` (default) | on the robot, local commands only | nothing |
| `robot` (recommended) | "hey R2-D2", spotted on the robot | only what you say after "hey R2-D2", until Home Assistant has the transcript (15 s at most) |
| `ha` | Home Assistant's wake word engine (openWakeWord), e.g. "okay Nabu" | all microphone audio, the whole time Home Assistant is connected |

In `robot` mode R2 chirps when he hears "hey R2-D2", then streams the request. Give him half a second after
the chirp: the microphone is muted while R2 plays his own sounds, so he doesn't send his own chirp to Home Assistant.
The microphone is also off while he speaks the reply, while music plays, and in the other cases where the
on-robot voice commands pause (patrol, pairing, projector clips). Turning voice off (`action voice-off`,
or the phone app) turns this off too.

The back light shows what's happening: slow green/yellow while listening, fast green/yellow while Home
Assistant thinks, and both on while R2 speaks. If the pipeline fails (nothing understood, or the AI is
unreachable), R2 makes his "didn't understand" sound.

## Setting it up
1. Install the new R2 Main and R2 Agent, then switch the satellite on:
   `tools\r2.ps1 assist robot`
2. Optional but recommended: only accept Home Assistant's own address:
   `tools\r2.ps1 assist-only 192.168.1.10` (`tools\r2.ps1 assist-only` with no address undoes it).
   Without it, any device on R2's own subnet can connect (the same rule as the API).
3. In Home Assistant: **Settings > Devices & services**. R2 should appear as a discovered **Wyoming Protocol**
   device (R2 announces itself over zeroconf). If not, use **Add integration > Wyoming Protocol** with
   R2's IP address and port **10700**.
4. Open the new R2 device and pick a pipeline under **Assist pipeline** (make it in **Settings > Voice
   assistants**). For an AI, choose a pipeline whose conversation agent is your AI integration (OpenAI,
   Anthropic, Google, Ollama...), with speech to text (Whisper, or Home Assistant Cloud) and text to
   speech (Piper, or Home Assistant Cloud).
5. Say "hey R2-D2, what time is it?"

For `ha` mode the pipeline also needs a wake word engine (the openWakeWord add-on), and you pick the wake
word on R2's device page in Home Assistant.

`tools\r2.ps1 status` shows the satellite under `assist`: mode, whether Home Assistant is connected, and what R2 last heard.
`tools\r2.ps1 assist off` switches it off and closes the port.

R2 serves one Home Assistant connection at a time. Home Assistant reconnects by itself after R2 restarts.
If Home Assistant is on a different subnet or VLAN, use `assist-only <its address>`. The pinned address is
accepted from anywhere.

## Answering in beeps
R2 can answer in droid beeps instead of the pipeline's voice, with the "translation" shown as subtitles:

`tools\r2.ps1 assist-reply beeps` (or the **Answer in beeps** switch on R2's web page or in Home Assistant, or
`action beeps-on`). `tools\r2.ps1 assist-reply voice` goes back to speaking. Kept across reboots.

- Home Assistant sends the answer's text just before its audio. R2 beeps generated chirps shaped like the answer
  ([chirps.md](chirps.md): about one tone per syllable, rising for a question, a trill for "!", at most 8 s) and
  drops the spoken audio. With `chirps-off` he uses a few factory sounds instead (one for a short answer, up to
  four for a long one).
- The text shows as a subtitle on R2's web page (Status, for 30 s), in `tools\r2.ps1 status` (`subtitle`), and in
  Home Assistant as the **Last reply** sensor, so an automation can show it on a screen or speak it somewhere else.
  The subtitle is there in voice mode too.
- Only answers to a request turn into beeps. Announcements (`assist_satellite.announce`), and any other audio Home
  Assistant sends without a request before it, still play as sent.
- The pipeline still needs text to speech: R2 uses the end of its audio to know the answer is over. Any voice works,
  since you won't hear it.
- Muted R2 shows the subtitle without beeping.

## Letting voice and the AI run R2's actions
Home Assistant needs a way to run R2's actions. These all go through R2's normal API (`POST /action?name=...`).
Put this into a [package](https://www.home-assistant.io/docs/configuration/packages/), e.g.
`packages/r2d2.yaml`, with R2's address, and put a token for Home Assistant in `secrets.yaml` as `r2_token`. Make one with
`tools\r2.ps1 client-add home-assistant -Plain` (control level: actions and routines, no installs or
reboots; see [security.md](security.md)). The robot's own owner key no longer works here.

```yaml
rest_command:
  r2_action:
    url: "http://192.168.1.50:7700/action?name={{ action }}"
    method: post
    headers:
      X-R2-Token: !secret r2_token

script:
  r2_action:
    alias: "R2-D2 action"
    description: >-
      Makes the R2-D2 robot do something with his body: move, dance, make a sound, switch his
      lightsaber or arm, or show a Princess Leia or Luke Skywalker hologram. Use it whenever the user
      asks R2 to do something physical. "stop" stops whatever he is doing.
    fields:
      action:
        description: The action for R2
        required: true
        selector:
          select:
            options: [dance, turn-left, turn-right, turn-around, forward, circle, patrol, shake-head,
                      who-are-you, noise, lightsaber, arm, leia, luke, flash-front, flash-back, stop]
    sequence:
      - action: rest_command.r2_action
        data:
          action: "{{ action }}"

intent_script:
  R2Action:
    action:
      - action: rest_command.r2_action
        data:
          action: "{{ r2_action }}"
    speech:
      text: ""
```

- **AI conversation agents:** expose the `R2-D2 action` script to Assist (**Settings > Voice assistants >
  Expose**). The AI gets it as a tool, so "hey R2-D2, show me Princess Leia" or "do a little dance"
  runs the action. In the AI's instructions you can add something like *"You are R2-D2. Keep answers short.
  Use the R2-D2 action script to move."*
- **Home Assistant's own agent** (no AI): add `custom_sentences/en/r2d2.yaml` in the config folder:

```yaml
language: "en"
intents:
  R2Action:
    data:
      - sentences:
          - "[(R2 | R2-D2 | artoo)] {r2_action} [please]"
lists:
  r2_action:
    values:
      - in: "dance"
        out: "dance"
      - in: "turn left"
        out: "turn-left"
      - in: "turn right"
        out: "turn-right"
      - in: "turn around"
        out: "turn-around"
      - in: "(go | drive) forward"
        out: "forward"
      - in: "walk [in] a circle"
        out: "circle"
      - in: "[go on] patrol"
        out: "patrol"
      - in: "shake your head"
        out: "shake-head"
      - in: "who are you"
        out: "who-are-you"
      - in: "make some noise"
        out: "noise"
      - in: "[switch] [your] lightsaber"
        out: "lightsaber"
      - in: "move [your] arm[s]"
        out: "arm"
      - in: "[show me] princess leia"
        out: "leia"
      - in: "[show me] [luke] skywalker"
        out: "luke"
```

  R2's own droid sounds are the reply, so the intent's spoken answer is empty.

**Routines** ([routines.md](routines.md)) run the same way, because `POST /action?name=` also runs a stored routine
by name. Add your routine names to the script's `options` (`GET /routines` lists them) and to the
`r2_action` list, and the AI or "hey R2-D2, <routine>" runs them.

If the Home Assistant MQTT integration for R2 is installed, its action buttons can be exposed to Assist
instead of the script.

## Rooms
R2 can drive to rooms you taught him ([places.md](places.md)). With the MQTT integration, expose the
**Go to** select to Assist, and "R2, go to the kitchen" works with an AI agent. For Home Assistant's own
agent, add a REST command and a sentence (the room names are the ones `GET /places` lists):

```yaml
rest_command:
  r2_goto:
    url: "http://192.168.1.50:7700/goto?place={{ place }}"
    method: post
    headers:
      X-R2-Token: !secret r2_token

intent_script:
  R2GoTo:
    action:
      - action: rest_command.r2_goto
        data:
          place: "{{ r2_place }}"
    speech:
      text: ""
```

```yaml
# custom_sentences/en/r2d2.yaml, next to R2Action
intents:
  R2GoTo:
    data:
      - sentences:
          - "[(R2 | R2-D2 | artoo)] (go | drive) to [the] {r2_place}"
      - sentences:
          - "[(R2 | R2-D2 | artoo)] (go | come) home"
        slots:
          r2_place: home
lists:
  r2_place:
    values:
      - in: "kitchen"
        out: "kitchen"
      - in: "living room"
        out: "living-room"
```

For the AI's script, add `goto:kitchen` style names to the `R2-D2 action` script's options, since
`POST /action?name=goto:kitchen` also sends him (and `go-home`, `tour`).

## Checking it without Home Assistant
`python3 tools/assist-check.py <robot-ip>` pretends to be Home Assistant. Disconnect Home Assistant's
Wyoming entry for R2 first (disable it), since R2 serves one connection at a time. It checks that R2
describes itself as a satellite and answers pings. Then you say "hey R2-D2" and something. It saves what R2
streamed to `heard.wav`, sends back a pretend transcript and a two-tone beep as the reply, and expects R2
to beep and report that he played it.

## Protocol notes
Wyoming events are a JSON header line (`type`, `data_length`, `payload_length`, `version`), followed by
the JSON data and the raw audio. R2 handles `describe` → `info` (with a `satellite` entry), `run-satellite`,
`pause-satellite`, `ping` → `pong`, `detection`, `transcribe`, `voice-stopped`, `transcript`,
`synthesize`, `audio-start`/`audio-chunk`/`audio-stop` (the reply, any rate, 16-bit, mono or stereo) →
`played`, and `error`. R2 sends `detection` and `run-pipeline` (start at `asr`) after "hey R2-D2" in
`robot` mode, or one `run-pipeline` from `wake` with `restart_on_end` in `ha` mode. The microphone audio
is 16 kHz, 16-bit mono in 0.4 s chunks. Home Assistant probes with a separate short connection
(`describe`), and then keeps one connection open for the pipeline.

## Limits
- The wake word on the robot is English "hey R2-D2" only, like the on-robot commands.
- Wyoming has no authentication. Mitigations: off by default, R2's subnet or one pinned address only, and in
  `robot` mode nothing is sent before the wake word.
- No follow-up questions without the wake word yet. Each request starts with "hey R2-D2".
- If Home Assistant doesn't reply with audio (e.g. the intent answers with empty speech), the back light
  returns to normal after 15 s.
- Turning the head toward the speaker isn't done: R2's microphone can't tell where a voice comes from.
