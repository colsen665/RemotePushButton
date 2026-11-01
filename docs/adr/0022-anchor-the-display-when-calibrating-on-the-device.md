# 22. A calibration saved on the device anchors the display on its photo

- Status: Accepted
- Date: 2026-10-18
- Amends: [0018](0018-follow-the-display-from-marks-around-the-row.md) (when the anchor is made)

## Context
- **Saving on the device means touching it.** Calibrating on the device phone takes taps on its screen (Save, the PIN), and each tap can move the phone in its mount.
- **On one device phone this broke every read.** After a calibration saved on the device, the digits sat about 12 px (0.15 digit heights) lower than calibrated in every later photo. That is beyond the reader's alignment search, so each press read `??????` or a lone digit.
- **The anchor came too late.** The device makes its anchor at the first valid read after a calibration (ADR 0018). With the row already off, no read was ever valid, so no anchor was made, and nothing followed the display.
- **The calibration photo is a good anchor.** It is a photo in the device's own geometry, and the user has just checked the read on it.

## Decision
- **Save on the device anchors at once:** when a calibration is saved on the device itself, the agent (`DeviceAgent.saveCalibration`) saves it and makes the anchor from the calibration photo, turned as calibrated. It does so only if that photo reads valid with the calibration, the same rule as for the first valid read. The log says "display anchored".
- **The photo stays on the device:** it is the 1600 px photo the editor already holds. It never goes to a controller or onto the relay.
- **Unchanged:** a calibration saved from a controller (`set_calib`) is still anchored by the read the device makes straight after saving. The device doesn't keep the photo a controller calibrated on. Following, the anchor itself and its limits are as in ADR 0018.

## Consequences
- **A move between Save and the first press is followed,** within ADR 0018's range. Tested by moving the `rig3` photos 12 px against an anchor made before the move: the device follows by 13 px and reads all three codes.
- **A calibration that doesn't read its own photo gets no anchor,** as before. The first valid read makes one.
- **Saving waits for a running job,** since it takes the job lock.
- **Remote calibration remains the better way,** because nobody touches the device phone (ADR 0016).
