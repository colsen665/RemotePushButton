# 13. The reader re-aligns on every read, and calibration refines itself

- Status: Accepted; amended by [0016](0016-locate-digits-from-the-code-and-remote-calibration.md) (the calibration screen locates the digits from the code; its live read uses the alignment search)
- Date: 2026-10-16
- Amends: [0008](0008-template-based-seven-segment-reader.md) (steps 2–6)

## Context
Real photos of the target display showed how narrow the reader's tolerance was. The LCD segments are thin and slightly blurred, so a calibration off by 2–3 px (about 2% of digit height) usually turned a read invalid. A mount that shifts slightly over time would therefore stop the reader. Calibrating by hand to that precision on a phone screen is also impractical. Photos from the final rig added two more problems:
- perspective, because the camera views the display at an angle;
- glare from the light across one digit, which pushed that digit's segments above a single threshold for the whole row.

## Decision
- **Threshold once, measure many times:** each frame is straightened once, with a margin of a quarter of the digit height around the calibrated row, then blurred and thresholded. Segments can then be measured for any placement of the row without straightening again.
- **Threshold per digit cell:** Otsu runs on each digit cell, and the threshold is interpolated between cell centres. A cell whose split doesn't look like segments on a background uses the whole row's threshold: too little contrast, or an ink share outside 4–50% (a blank digit, a shadowed edge). This handles glare and uneven light.
- **Alignment search on every read** (`alignSearch`, default 0.06 of digit height, about 5 px):
  1. Try every whole-row shift within that radius, and keep the one where segments split most cleanly into on and off. Only patterns that are real digits score well, so blank background can't win. On a tie, the calibration as saved wins.
  2. Then move each digit by up to 1 px, in half-pixel steps, picking by that digit's confidence first.
  
  The result reports the row shift it applied.
- **No dropped segments:** misalignment loses segments rather than adding them, and the search favours clean reads, so it could turn a 9 into a 3. Any digit with ink right beside a segment it reads as off is marked unknown. The checked band extends half a stroke width on each side of the segment.
- **Refine** (`SevenSegCalibrator.refine`): from rough corners, it searches a coarse grid of shifts and widths, then hill-climbs the four corners, and optionally the slant, gap and thickness. It then re-thresholds with the refined corners and repeats. Corners move projectively, so Refine can also correct the perspective of a rough calibration. It accepts several frames.
- **The calibration screen asks for the code shown,** and Refine runs only once it is entered. Without the code, Refine sometimes found fits that read clearly but wrongly (8→3, 9→3). The live read on that screen runs without the search, so it shows how good the calibration itself is.

## Consequences
- **On the photo fixtures:**
  - every row shift of up to 3 px reads correctly;
  - over 360 random corner errors of up to ±6 px, 243 read correctly and the rest were rejected, with no wrong code reported as valid (152 correct reads, with misreads, before the per-cell threshold and the beside check);
  - one Refine run reads all three rig photos.
- **Cost:** a read takes tens of milliseconds on a desktop JVM. Refine takes 0.5–2 s for one to three frames there, more on the phone, so it runs off the main thread with a progress label.
- **A stored config without `alignSearch` gets the search,** since new fields take their defaults.
- **Still deliberate:** a segment that is physically missing still reads like an off segment (ADR 0008). The beside check doesn't change that, because it only looks for ink next to the template.
- **Possible follow-up:** the reported shift can show drift before reads fail. It isn't sent to the controller yet.
