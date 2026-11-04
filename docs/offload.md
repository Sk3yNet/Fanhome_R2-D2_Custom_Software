# Moving heavy work off the robot

R2's main board is an Allwinner H3 (four Cortex-A7 cores) with 512 MB of RAM. It runs what the factory
software ran: keyword-spotting voice commands and simple face detection. Anything bigger, such as
understanding free speech, recognizing who a face belongs to, or natural-sounding speech, should run on
another machine in a container, with R2 as the microphone, speaker, camera and body.

Everything on the robot keeps working without the container. The container only adds abilities, and the
robot never depends on it for safety.

## Where it can run
- **Home Assistant add-on:** for people who already run Home Assistant OS or Supervised.
- **Plain Docker / Docker Compose:** for everyone else, e.g. on a NAS, a Raspberry Pi 4/5 or a PC.

The same images work in both. The add-on is a thin wrapper around the Docker image.

## Voice
Option chosen: make R2 a **Wyoming satellite**. Wyoming is Home Assistant's open protocol for voice
satellites, and the voice services already exist as containers:

| Job | Service (container) |
|---|---|
| Wake word | openWakeWord |
| Speech to text | faster-whisper (Whisper) |
| Text to speech | Piper |
| Understanding | Home Assistant Assist, or any conversation agent configured there |

How it fits together:
1. R2 Main gains a Wyoming satellite endpoint (TCP, JSON event lines plus raw audio). Home Assistant
   connects to it and adds R2 as a voice assistant device.
2. R2 streams 16 kHz microphone audio while it listens; Home Assistant runs the wake word and speech
   recognition, and sends back the reply audio, which R2 plays (like the DLNA media player already does).
3. R2's own actions are exposed as Home Assistant intents: "R2, dance", "turn left", "show me Princess
   Leia". Each maps to the same actions as the on-robot commands (``POST /action?name=...``).
4. Free questions ("what's the weather") go to whatever conversation agent Home Assistant uses, and R2
   speaks the answer, optionally after a droid chirp.

On-robot keyword spotting stays as the default and the fallback: if the satellite connection is down,
"hey R2-D2" still works. Only one of the two listens at a time, chosen in R2's settings.

## Vision
R2's camera is already an MJPEG stream (``/camera.mjpg``), which most vision containers accept.
- **Face recognition:** e.g. CompreFace or Double Take with Frigate. They recognize who is in front of R2.
  R2 can greet known people with their own sound or projector clip, and ignore strangers.
- **Object or person detection:** Frigate. It can trigger routines or patrol behavior.

Results come back through R2's API (actions, head moves) or as Home Assistant automations. On-robot
face detection keeps doing the simple part (turning the head toward a face), so a container is only
needed for knowing *who* it is.

## Security
The container talks to the same token-protected API as everything else, from the local network only.
Audio and video leave the robot only to the configured container host, and only while a feature that
needs them is active.
