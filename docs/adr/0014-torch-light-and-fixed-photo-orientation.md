# 14. Torch light and a fixed photo orientation

- Status: Accepted; amended by [0016](0016-locate-digits-from-the-code-and-remote-calibration.md) (the rotation is found from the code, on the photo already taken)
- Date: 2026-10-16
- Refines: [0012](0012-camera-open-only-during-jobs.md) (camera use during jobs)

## Context
- **Light:** the rig keeps the display in complete darkness, so the phone has to light it itself. A flash fires only at the moment of capture, after auto-exposure has metered without it, and its brightness varies. That works against the consistent photos the reader depends on.
- **Orientation:** the stock camera app picks photo orientation from the tilt sensor, which is unreliable when the phone faces straight at a display. CameraX's default target rotation follows the screen rotation at the moment the camera is set up.
- **Display turned 90°:** in the rig, the display sits rotated relative to the camera image, so the digit row runs vertically in the photo. The photo sent to the controller as a backup should still read upright.

## Decision
- **Torch:** `CameraCapture` switches the torch on when it binds the camera for a job, and the torch goes off when the camera unbinds (ADR 0012). Exposure settles under the same light during the press and its delay. The flash is always off. A device setting, *Torch while taking photos*, is on by default.
- **Fixed orientation:** the `ImageCapture` target rotation is pinned to the phone's natural orientation (`ROTATION_0`). Photos then keep the same orientation however the phone is tilted or the screen is rotated.
- **Photo rotation setting:** `photoRotation` (0, 90, 180 or 270° clockwise) is added on top. The calibration screen sets it with *Turn 90°*, which resets the corners, and saves it with the calibration. The reader and the controller's backup photo both get the turned image.

## Consequences
- The photo test fixtures from the rig are stored turned upright, as the app delivers them with `photoRotation` = 90.
- The torch reflects off the LCD. In the rig photos, glare washed out one digit. The per-cell threshold (ADR 0013) handles it, but the light angle is still worth checking on the rig.
- The torch adds power draw and heat only while the camera is open, for about 10 s per job.
- Settings stored with the old `flash` field fall back to the defaults: torch on, no rotation.
