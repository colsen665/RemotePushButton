# 8. On-device, template-based 7-segment reader (no OCR library)

- Status: Accepted; amended by [0013](0013-reader-alignment-search-and-refine.md) (per-cell threshold, alignment search, Refine), [0017](0017-read-from-a-full-size-crop-of-the-row.md) (reads a full-size crop of the row)
- Date: 2026-10-15

## Context
The target shows a short **digit code on a 7-segment LCD or LED display**. The device must turn the photo into text and send that first, with the photo as a backup.

Common text recognisers, such as ML Kit and Tesseract, read 7-segment digits poorly. On the other hand, the phone is rigidly mounted, so the digits are always in the same place.

## Decision
`SevenSegReader` is pure Kotlin and works on the device phone, in the same spirit as the `ssocr` tool.
1. **Calibrate once:** the user marks the four corners around the digit row, sets the digit count, how slanted the digits are, whether segments are dark-on-light or lit-on-dark, the gap between digits, and the segment thickness.
2. **Straighten the area:** a homography computed in pure Kotlin (not `android.graphics.Matrix`) maps the calibrated area to a 96 px-high rectangle, so the step is JVM-testable.
3. **Prepare:** apply a light blur, then threshold with Otsu's method. If contrast is under 20 grey levels, treat the area as having no ink at all.
4. **Measure:** for each digit, measure the ink coverage in the inner 60% of each segment's area, leaving a 20% margin on each side. A segment is on at ≥ 50% coverage.
5. **Look up:** map each 7-bit pattern to a digit. Common variants are accepted (6 with or without the top bar, 7 with or without the upper-left bar, 9 with or without the bottom bar).
6. **Confidence:** each segment's confidence is how far its coverage sits from the on/off threshold. A digit's confidence is its weakest segment's, and the overall figure is the lowest across digits. The result is valid only if it has exactly N known digits, each with confidence ≥ 0.35.
7. **Retry:** if the result isn't valid, take up to two more frames about 150 ms apart.
   - LED mode (multiplexed displays) takes the highest coverage for each segment across frames.
   - LCD mode keeps the best single frame.

## Consequences
- **Small and fast:** no ML model or OpenCV dependency, and every step is deterministic and unit-tested on synthetic displays covering rotation, slant, noise, blur, low contrast, partial segments and multiplexing.
- **Limitation:** a segment that's *physically* missing is indistinguishable from an off segment. A broken display can therefore read as a different valid digit. Partly hidden segments are flagged as low confidence. The photo is always sent so the user can check.
- **Needs re-calibration** if the phone or display moves. The controller's `calib_photo` command lets you check alignment remotely.

## Alternatives
- **ML Kit or Tesseract:** poor on 7-segment digits.
- **A trained CNN digit classifier:** more robust to geometry, but needs training data and a model.
