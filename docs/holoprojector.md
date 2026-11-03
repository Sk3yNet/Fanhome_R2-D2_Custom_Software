# Holoprojector hardware notes

Notes on the robot's holoprojector, and options for custom content or a more capable replacement.
Facts come from a published teardown ([adrirobot.it](https://www.adrirobot.it/r2-d2_proiettore_primario/),
in Italian) and photos of the board. Anything marked *inferred* is not confirmed yet.

## How it works
- **Optics:** a small transmissive LCD (two thin glass plates, no backlight of its own) sits behind an
  adjustable lens group. A high-brightness white LED shines through the LCD, and the lens projects the image.
- **Control:** the main board sends `{"cmd":"projector","mode":1|2|0}` to the controller and plays the
  matching audio clip. The controller switches the projector on and off. No image data ever leaves the main
  board; the clip lives on the projector board.
- Confirmed: "projector 1" plays a Princess Leia clip and "projector 2" a Luke Skywalker clip, each once
  and then stopping. *Open question:* whether that's one board told which clip to play or two boards.
  The documented cable has a single enable line, which fits two boards, but the 4 MB flash could hold both clips.

## Projector board ("R2D2 – Projector", about 47 × 26 mm)
| Ref | Part | Role |
|---|---|---|
| U1 | Winbond **W25Q32JV** (W25Q32JVSSIQ) | 32 Mbit (4 MB) SPI flash. *Inferred:* holds the image/animation data. |
| U2 | STC **15W40x** family, LQFP44 (board marking "STC 15W408S"; the teardown's datasheet link says STC15W404S) | 8051-family microcontroller: reads the flash and drives the LCD |
| U3 | "7333A" | 3.3 V low-dropout regulator, 250 mA |
| CN1 | FPC connector | LCD panel, marked **1SG44C735CP03** |
| 3-pin | **VCC, GND, EN** | command cable to the robot. Supply voltage not documented. |
| 2-pin | **+, GND** | to the LED board |
| pads | **GND, RXD, TXD, 3.3V** (back of board) | serial programming of U2; other unidentified pads may give access to more functions |

- *Inferred:* the panel is a 1.44" 128 × 128 colour TFT with an ST7735 controller. The "44" and "C735" in the
  marking match how those panels are usually numbered.
- *Inferred:* at 128 × 128 and 16-bit colour, one frame is 32 KB, so the 4 MB flash holds about 128 frames
  (roughly 10 s at 12 fps), which is a short animated clip rather than a still image.

## LED board ("R2D2-TY-LED", 22 × 22 mm, aluminium substrate)
White LED, 1 W, 3.0–3.6 V, 350 mA, 120–140° beam. The teardown says "warm white (7000 K)", which contradicts
itself: 7000 K is cool white.

## Lenses
Lens elements of 17, 21 and 22 mm (supplied in build issue 29).

## Custom content on the existing projector
1. With the projector **unpowered and disconnected** from the robot, read U1 with an SPI programmer
   (e.g. a CH341A with a SOIC-8 clip) and `flashrom`. **Keep that dump**: it's the way back to stock.
2. Work out the format: view the dump as raw 128 × 128 RGB565 frames, and look for a header (frame count,
   frame rate).
3. Write converted frames in the same format back to the chip. Nothing else changes: the controller still
   just switches the projector on.

Use 3.3 V only. One clip per projector board; choosing images on demand needs new firmware or a new
board (below).

## Replacing it with a more capable setup
The constraints are the space in the dome, the existing lens path, heat, and the robot's 5 V power budget.
Measure the projector housing (panel area, distance from panel to lens, room behind the LED) before buying parts.

| Option | What you get | Effort and risks |
|---|---|---|
| **A. New controller board, same LCD and optics** (e.g. ESP32-S3 or RP2040 driving the existing panel) | Many clips, choice of image on demand, Wi-Fi uploads, live frames from the main board or Home Assistant | Needs the panel's FPC pinout. Firmware plus a small adapter PCB. Resolution stays 128 × 128. Optics and fit unchanged, so the lowest risk. |
| **B. Higher-resolution transmissive LCD** (e.g. a 1.3–1.54" 240 × 240 ST7789 panel with its backlight removed) driven by an ESP32-S3 | Everything in A, with about 3.5× the pixels in nearly the same panel size | Removing the backlight is a common DIY projector trick but fiddly. Lens focus may need adjusting. Check that the panel fits the housing and that the LED is bright enough through the new panel. |
| **C. Pico DLP projector** (e.g. a TI DLP2000-class light engine at 640 × 360, fed by a small Linux board such as a Pi Zero 2 W over its parallel display port) | Real video, much brighter, and a proper media target for Home Assistant | Much larger than the current module, about 1–2 W plus a dedicated computer, heat in a closed dome, and new optics. Fit is the big unknown. |

Recommended path: **A first, then B.** A keeps everything that already works (optics, LED, the controller's
on/off signal) and adds on-demand images, Wi-Fi and live control. B is a drop-in resolution upgrade on the
same new board. Only consider C if measurements show there's room and real video quality matters.

Compatibility for A and B: the new board should still honour the controller's EN line (on means play the
default clip), so the robot behaves normally even without our software. Extra commands come over Wi-Fi or a
UART link from the main board (the H3 has spare UARTs).

## Streaming from the main board
Goal: the main board decodes the media (from Home Assistant, a routine, or a file), scales it to the panel,
and streams frames to the projector live, with the audio on the robot's speaker in sync.

**Bandwidth.** Raw 16-bit frames are too much for a serial link:

| Panel | Raw at 15 fps | As JPEG (about 8–15 KB/frame) at 15 fps |
|---|---|---|
| 128 × 128 | ~0.5 MB/s | ~0.1–0.2 MB/s |
| 240 × 240 | ~1.7 MB/s | ~0.15–0.25 MB/s |

So send **compressed frames (MJPEG)**. The main board encodes JPEG frames (Android's own encoder is fine at
this size), and an ESP32-S3 decodes them in hardware-friendly code and pushes them to the LCD.

**Link options:**
- **Wi-Fi (recommended):** the ESP32-S3 joins the robot's network and receives frames over UDP or TCP from
  R2 Main. It needs only power and ground, plus EN for compatibility, so no new wires through the dome. It
  handles 240 × 240 at 15–25 fps comfortably. Latency is tens of milliseconds, covered by timestamping frames
  against the audio.
- **UART on spare main-board pins:** a wired fallback at 2–3 Mbaud, enough for JPEG at 128 × 128 or
  low-rate 240 × 240. It needs two extra wires to the projector and the H3's UART pins to be reachable.
- **Driving the SPI panel straight from the H3:** fewest parts, but it needs the H3's SPI pins routed to the
  dome and a kernel display driver this Android build doesn't have. Not recommended.

**Main-board side (R2 Main):** decode the source with Android's media decoder (in a sandboxed process,
since media decoding is a known weak spot on 4.4, see roadmap section 4), scale to the panel, encode JPEG,
timestamp each frame, and send it. Play the audio locally and use its clock to pace the frames. Fall back
to the projector's stored default clip if the stream stops.

**Physical checks before building:** where the main board sits relative to the dome, what passes through
the dome joint, and the supply voltage on the projector cable. The ESP32-S3 draws about 0.3–0.5 W on Wi-Fi,
on top of the 1 W LED.
