# 19. The wait before the photo is 3 s by default, set per device on the controller

- Status: Accepted
- Date: 2026-10-17

## Context
- **Fixed in the app:** after the Bot presses, the device waits `delayMs` from the `press` command before it photographs the display. The controller app always sent 1000 ms.
- **The display needs longer:** the photo must wait until the display shows the new code, and how long that takes depends on the display.
- **Hands off the rig:** a setting on the device phone means touching the mounted phone, which can shift the calibration (ADR 0016).

## Decision
- **Per device, on the controller:** each pairing on a controller stores its own delay (`StoredPairing.delayMs`). The controller sends it in every `press`.
- **Default:** 3000 ms (`ControllerClient.DEFAULT_DELAY_MS`), for new pairings and for pairings stored before this change. Scanning a device's QR again keeps its delay.
- **Range:** 0 to 10 s in half-second steps, up to the device's existing cap (`DeviceAgent.MAX_DELAY_MS`). The controller screen shows "Photo 3 s after the press"; tapping it opens the setting.
- **Wire default unchanged:** `Press.delayMs` stays 1000 when absent. CBOR leaves out fields that equal their default, so older controllers send their fixed 1 s as an absent field. The new default of 3000 always travels explicitly.

## Consequences
- **Protocol:** no change. An older device honours the delay sent, up to its 10 s cap.
- **Older controllers** keep sending 1 s until they are updated.
- **Several controllers** each keep their own delay for the same device. Each has to be set separately if the default doesn't suit.
- **Timing:** a press takes about 2 s longer by default. The controller's job timeout (60 s) leaves room for the 10 s maximum. `pressedAt` and `capturedAt` still show the actual wait (ADR 0010).
