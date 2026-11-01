# 18. The device follows the display from the marks around the digit row

- Status: Accepted
- Date: 2026-10-17
- Amends: [0017](0017-read-from-a-full-size-crop-of-the-row.md) (where the row is cut)

## Context
- **Reads tolerate only a tiny shift.** The device reads at the calibrated corners. The reader's alignment search (ADR 0013) only corrects a shift of up to 0.06 digit heights. On the rig that is about 6 px of the 1600 px photo, under half a millimetre on the display.
- **A bump means recalibrating.** A Bot press, or a touch on the rig, can move the display or the device phone further than that. Reads then fail until someone recalibrates.
- **The surroundings don't change.** Around the digits are things that sit on the same body as the digits, so they move with them, and that look the same in every photo. On the display these are the LCD window frame and the logo.
- **The digits can't be used.** Searching the digits further than 0.06 digit heights would risk settling on a wrong alignment, and so on a wrong code.

## Decision
- **Anchor** (`DisplayAnchor`): the 1600 px photo around the calibrated row.
  - It covers the row's bounding box plus 3 digit heights to the left and right (enough for the logo) and 1.5 above and below.
  - It is reduced to 16 px per digit height.
  - The row, widened by 0.3 digit heights, is blanked, so the anchor holds no code. The blanked area is recomputed from the calibration, never stored.
  - It records the calibration it was made for, and applies only to that calibration.
- **When it is made:** at the first valid read after a calibration, from that job's first frame, read at the calibrated position. That covers a remote save (`set_calib` reads straight away), a save on the device (the next job) and existing installs. There must be at least 6 tiles with contrast, so a blank scene gets no anchor.
- **Following, on every read, in the order orientation, find, crop, read:**
  1. A coarse search at half resolution matches the whole patch, by normalised cross-correlation, within ±2 digit heights.
  2. **Tiles as markers:** the patch is cut into tiles one digit height square. Tiles that are mostly blanked, or plain plastic, are skipped. Each tile is found on its own, within 0.6 digit heights of the coarse shift, to a fraction of a pixel. A tile holding only a straight edge counts only across that edge.
  3. **Consensus:** a shift, turn and scale (a similarity transform) is fitted to the tile matches by least squares. The worst tile is dropped until every tile fits within 0.1 digit heights. This outvotes tiles hit by glare, and tiles on background that didn't move with the display.
  4. **Second pass:** the photo is moved back by that fit and every tile is matched again, within 0.2 digit heights. Tiles that are no longer turned or scaled against the photo match precisely. The result is the second fit.
  5. **Accepted only if:**
     - at least 6 tiles agree, with at least 3 for each axis;
     - the tiles that pin the vertical spread at least 2 digit heights along the row;
     - the turn is within ±5° and the scale within ±10%;
     - the moved corners stay inside the photo.
     
     Otherwise the device reads at the calibrated position, as before.
- **Reading:** the agent moves the calibrated corners by the fit, cuts the full-size row there (`Frame.fullSize`, a `RowCutter`), and reads every frame of the job at that one position. If that read isn't valid, it also reads at the calibrated position and keeps that read if it is valid.
  - The camera no longer knows the calibration; the agent decides where to cut.
  - Following, cutting and reading run on `Dispatchers.Default`. Before, the read ran on the service's main thread.
- **Unchanged:** the saved calibration and the anchor stay as they are. Each read is measured against the calibration, so errors don't pile up from one read to the next.
- **Log:** "display anchored", "display followed: moved x, y px, turned °, scaled", "display not found around its calibrated position", and "read at the calibrated position instead". None of these lines contains a code.

## Consequences
- **On the rig photos:**
  - Shifts of up to about 1.9 digit heights, turns of a few degrees (tested to −5°) and scale changes of ±8% are followed to within about 1 px. Without following, the read fails.
  - New digits on a display that stayed put move nothing.
  - The anchor from the first rig position finds the display at the second (about 2 digit heights away, turned 2°) and reads it.
  - It finds nothing in a blank image, in the photo turned 180°, or in a hand-held photo of the same display.
- **Beyond the range:** a display that moved more than about 2 digit heights or 5°, or whose apparent size changed by more than 10%, still needs recalibrating. The device then reads at the calibrated position and logs that it didn't find the display.
- **Perspective:** the fit allows only a shift, a turn and a scale. A camera that is tilted against its old position leaves a residual error, which the reader's own alignment search absorbs while it stays small.
- **Cost:** about 35 ms per read on a desktop JVM, plus one more full-size cut when the read at the followed position fails.
- **Storage:** the anchor is about 10 KB of pixels (14 KB as Base64), stored in the Keystore-encrypted settings and never sent. It is a reduced picture of the display with the digits blanked.
- **Protocol:** unchanged. Controllers still see and edit the saved calibration. If the display has moved since calibration, the corners in the calibration photo no longer sit on the digits, while the read is still correct.
