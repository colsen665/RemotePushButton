# 16. Calibration finds the digits from the code, and controllers can calibrate remotely

- Status: Accepted; amended by [0021](0021-calibration-photos-can-press-first.md) (calibration photos press the button first, by default)
- Date: 2026-10-16
- Amends: [0009](0009-device-app-as-home-launcher.md) (where calibration runs), [0013](0013-reader-alignment-search-and-refine.md) (the calibration screen), [0014](0014-torch-light-and-fixed-photo-orientation.md) (how the rotation is chosen)

## Context
- **Placing corners is hard:** on the rig photo, the digit row is about 330 × 100 px of a 1600 px frame. On the device screen that is roughly 200 × 60 px, smaller than a fingertip. Refine (ADR 0013) only corrects corners that are already within about a tenth of the digit height, so the user still had to place four corners precisely, set the digit count, the polarity and the turn by hand.
- **Touching the rig moves it:** the device phone is mounted rigidly in front of the display. Tapping and dragging on its screen can shift the camera, which invalidates the calibration being made.

## Decision
- **Locate from the code** (`SevenSegLocator`): the user enters the code the display shows; the app finds everything else, in the order orientation, crop, read.
  1. **Orientation:** a matched filter built from the segment template scans a copy of the photo reduced to 800 px, along each axis. It searches position, digit height, cell width and tilt, in both polarities.
     - Every placement is scored twice from the same ink measurements: for the code upright, and for the code turned 180° (reversed, each digit's segments turned a↔d, b↔e, c↔f). The better fit decides which way up the display is.
     - A 1, 3, 4 or 7 settles it, because none of them turned is a digit; a 1 on the left bars is a turned 1. A code without any of them reads the same both ways (`upsideDownToo`); then the turn listed first wins, which is the current one, and the screen warns.
     - The ink mask is a local-mean threshold with a window of one digit height, so glare doesn't wash out a digit. The coarse scan runs on dilated ink, because the thin LCD strokes give a peak narrower than the grid step. The best few candidates per scale and shape are hill-climbed on the exact ink, with slant added, and with thin background bands above and below the digits, which pin the height. A digit drawn in more than one way (a 6 with or without its top bar) scores with whichever pattern fits best.
  2. **Crop:** the best candidates in that orientation give the digit row at full size. The preferred axis is searched first; candidates scoring at least 0.7 are fitted before the other axis is searched.
  3. **Read:** Refine fits the row. The first fit that reads the code, valid, with the alignment search as the device reads, is the result.
  
  It returns the turn, the corners, the polarity, the digit count and the fitted shape.
- **Refine moves edges too:** besides single corners, it moves each edge's two corners together. One corner at a time only skews the row, so a level start stayed stuck on a row tilted by 2°.
- **Calibration screen** (shared by the device and controllers): enter the code, tap **Find digits**, check the read, **Save**. Corners, Refine, *Turn 90°*, the digit count, sliders and switches stay under *Adjust by hand*. Turns are tried on the photo already taken instead of retaking it. The live read now uses the alignment search, as the device does, and shows how far it re-aligned, instead of reading without the search (ADR 0013).
- **Remote calibration:** a controller can open the same screen. The protocol gains:
  - the `calib` event: the device's calibration (reader config and photo rotation), sent before `text` in `calib_photo` and `set_calib` jobs;
  - the `set_calib` command: the device checks the calibration (`Calibration.problem`), saves it, then photographs and reads with it like `calib_photo`, so the controller sees the device's own read.
  
  The calibration is not secret and carries no code. It travels encrypted like every other message.

## Consequences
- **On the photo fixtures:** the locator finds all 16 photos from the code alone, within 10 px of the calibrations, in every quarter turn. It finds nothing for a code one digit off, nor for the upside-down reading of the second rig position's code. It also finds a synthetic LED display and its polarity, and flags a code that reads the same upside down.
- **Cost:** on a desktop JVM, about 2 s when the photo is already the right way round and about 3 s when it is turned; at most two axis searches plus the fits. It runs off the main thread with a progress label.
- **Codes without a 1, 3, 4 or 7** can't show which way up the display is. After the first calibration the current turn is kept; for the first one, the screen asks to check the photo or use another code.
- **Who may calibrate:** any paired controller can now change the calibration. A bad one can only make reads fail or read wrongly, which the photo shows; a paired controller can already press. The device refuses values that would break the reader (corners outside the photo, a degenerate row, out-of-range shape values).
- **Compatibility:** an older device drops `set_calib` as malformed, so the controller times out waiting for the ack; for `calib_photo` it sends no `calib` event, and the controller asks for an update. An older controller ignores the `calib` event. No version bump: the new message types are additive.
- **Rig:** the calibration can be made and redone without touching the device phone.
