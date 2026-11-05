# Follow me

R2 drives after a person, using only the camera. It's meant for places R2 doesn't know (no taught routes
needed). *Built, not yet tested on the robot.*

## Using it
- Say "hey R2-D2", then "follow me". Or press **Follow me** in Home Assistant or on the web page, or run
  `tools\r2.ps1 action follow` (`python tools/r2.py action follow` works the same way).
- Stand in front of R2, facing him, 1 to 2 metres away, with the light on your face. R2 chirps when he
  starts looking and makes a second sound when he has locked on. Then walk off; R2 follows at his own (slow)
  pace and stops when he is about as close as when he locked on.
- To end it: "stop", the stop button, any other action, running `follow` again, or driving with the phone
  app. It also ends by itself after 30 s without seeing you, after 15 minutes, or when the battery is low.

## How it works
1. **Who to follow.** Android's face detector (the same one as face tracking) finds your face. The
   clothes under the face become the target: a colour histogram of that region, weighted against the
   rest of the picture, so colours that are everywhere in the room count for little. R2 refuses to lock on
   (the "didn't understand" sound) if no face shows up within 10 s, or your clothes look too much like the
   room.
2. **Tracking.** About 6 times a second, the picture is sampled onto a coarse grid (60 x 80 cells, upright)
   and the clothes are found again with mean shift. A person walking away shows R2 their back, which is why
   clothes are tracked rather than the face. How *wide* the clothes look, compared with when R2 locked on,
   tells whether you are further away or closer (width, because close up your clothes run off the bottom
   of the picture). If you are lost and turn round so R2 sees your face, he locks on again.
3. **Driving** (`FollowControl`). The head stays facing forward, so the picture shows where R2 drives.
   - More than about 14 degrees off to one side: turn on the spot (`move` angle ±90, power 25) until you're
     within 6 degrees.
   - Further away than at the start (width below 0.8 of it): drive forward at power 30 to 50, steering
     gently (`move` angle up to ±35), until back to 0.95.
   - Closer, lost, or no fresh camera result: stop. R2 never reverses.
   Drive commands are repeated every 200 ms, so the agent's motion watchdog (3 s) stops R2 if R2 Main hangs.

## Safety limits
R2 has no obstacle, bump or drop sensors that R2 Main can read (the controller may have some; see
ROADMAP), so follow me only drives where you just walked, slowly, and stops whenever it is unsure. Don't
use it near stairs or drops. The agent's low-battery stop and motion watchdog apply as usual. With the drive lock on
([drive-lock.md](drive-lock.md)), follow me only starts while driving is enabled, and stops when it locks.

## Tuning
The numbers are first guesses and live at the top of `apps/main/src/dev/r2hack/main/FollowControl.java`
(turning, speeds, distances, timeouts) and `FollowTracker.java` (how sure the tracker must be). The
"follow me" line of `/status` shows what R2 is doing (following, lost, can't lock on and why), and
R2 Main's log (`R2Brain`) records each stop and why.

## Robot tests still needed
- The voice phrase "follow me" is heard reliably and not set off by conversation (threshold `1e-16` in
  `Voice.java`).
- R2 locks on in normal room light, in front of a plain wall and a busy background.
- Turning on the spot: power 25 is enough to turn, and R2 doesn't overshoot badly (the camera's view
  angle of about 40 degrees across is a guess too).
- Following: drives when you walk away, curves the right way, and stops at about the starting distance.
- Lost: stops when you leave the picture, picks you up again when you come back or face him, and gives up
  after 30 s.
- Stop by voice works while driving (drive noise may drown it out).
