# 12. The camera is open only during jobs

- Status: Accepted
- Date: 2026-10-16
- Refines: [0009](0009-device-app-as-home-launcher.md) (camera ownership)

## Context
`CameraCapture` kept the camera bound for as long as the service ran, so exposure and focus were already settled when a press came in. That keeps the camera indicator on permanently, and the sensor streaming all the time adds heat and power draw on a phone that runs around the clock (ADR 0002). A press job already waits for the Bot press plus the 1 s delay before it takes the photo, which is longer than the camera needs to open and settle.

## Decision
- **Open per job:** `FrameSource.prepare()` is called when a job starts, before the press. On Android it binds the `ImageCapture` to the service lifecycle in the background.
- **Settle time:** a capture waits until the camera has been bound for at least 1 s, so the first frame isn't taken before auto-exposure settles. For a press this wait normally overlaps the press and its delay. Calibration photos, which have no press, take about 1 s longer.
- **Close when idle:** the camera is unbound 10 s after the last capture, or after a `prepare()` that isn't followed by a capture (for example when the press fails). Retakes and OCR retries within that window reuse the open camera.
- The service still owns the camera, and calibration still uses still photos through it (ADR 0009 unchanged otherwise).

## Consequences
- The camera indicator is off between jobs, and the camera adds no heat or power draw while idle.
- The camera is reopened by the background service, often with the screen off. This relies on the service having been started from the foreground (ADR 0009) and needs checking on the device phone.
- A failed bind is no longer logged at service start; it shows up as a `camera_failed` error on the first job.
