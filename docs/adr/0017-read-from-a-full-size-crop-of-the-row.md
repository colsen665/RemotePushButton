# 17. The reader reads a full-size crop of the digit row

- Status: Accepted; amended by [0018](0018-follow-the-display-from-marks-around-the-row.md) (the row is cut where the display is found)
- Date: 2026-10-17
- Amends: [0008](0008-template-based-seven-segment-reader.md) (what the reader reads)

## Context
`CameraCapture` scaled every photo down to 1600 px on the long side, and the reader then cut the calibrated row out of that. The camera's own image is larger, so most of the resolution was gone before the row was cut. On the rig the digits are still about 100 px tall at 1600 px, about the 96 px the reader straightens the row to. A display further away, or a smaller one, would get its digits upsampled from fewer pixels, with blurred thin LCD strokes.

## Decision
- **Order:** orientation, then crop, then read. The photo rotation is known from calibration (ADRs 0014, 0016). `CameraCapture` decodes only the calibrated row, plus 0.6 digit heights on every side, from the full-size JPEG (`BitmapRegionDecoder`). It turns that crop upright, makes it greyscale and attaches it to the frame as a `RowCrop`.
- **Geometry in core:** `RowCrop.plan` maps the upright row region back into the camera image for its rotation and rounds it outwards to whole pixels. The reader gets the region the crop covers exactly, so the calibration's corners map onto the crop (`SevenSegConfig.inRegion`). Calibration itself is unchanged: it still works on the 1600 px photo in fractions of its size.
- **Reader:** `DeviceAgent` reads the crops when every frame has the same one, else the scaled photos. A row taller than the 96 px it is straightened to is averaged over several samples per pixel instead of skipping most of them, so the extra pixels reduce noise rather than alias.
- **Fallback:** if the crop can't be cut (decoder failure, or a crop over 4 MP, e.g. the uncalibrated default row), the reader uses the scaled photo as before, and the device log says why.

## Consequences
- **Resolution:** reads get the camera's full resolution for the row, up to the 96 px row height, plus averaging above it. Calibrated rows that are small in the photo gain the most.
- **Unchanged:** the photo sent to controllers, and what the calibration screen shows and reads. The calibration screen's live read uses the scaled photo, so it can differ slightly from the device's read. A remote save reports the device's own read (ADR 0016).
- **Cost:** decoding a small region of the JPEG next to the scaled photo; a few tens of milliseconds.
- **Tests:** fixture photos enlarged 2.5 times, turned to each sensor orientation and cut, read correctly. The relay tests read only from the crop: their scaled photo is blank.
