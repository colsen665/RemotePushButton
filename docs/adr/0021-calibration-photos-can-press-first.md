# 21. Calibration photos press the button first, by default

- Status: Accepted
- Date: 2026-10-17
- Amends: [0016](0016-locate-digits-from-the-code-and-remote-calibration.md) (calibration photos were taken without pressing)

## Context
- **The display is dark between presses:** the rig keeps the display unlit, and it shows a code only after the button is pressed. A calibration photo taken without a press shows no digits, so **Find digits** has nothing to find.
- **Pressing separately is awkward:** the user had to press first (**Press** on a controller, **Test press** on the device) and then take the calibration photo before the code went away. Remote calibration exists so nobody touches the rig (ADR 0016), and a separate press adds a round trip.
- **The confirmation read after Save** also needs a code on the display. Without one, it reads nothing, and the device can't make its display anchor from that read (ADR 0018).

## Decision
- **Checkbox on the calibration screen:** "Press the button first, then wait N s", on by default, in both modes. It applies to the first photo, **Retake photo** and, from a controller, the photo after **Save**.
- **Protocol:** `calib_photo` and `set_calib` get an optional `pressDelayMs`. When it's present, the device presses, waits that long (capped at 10 s, as for `press`) and then takes the photo, as in a `press` job; `text.pressedAt` is then set. When it's absent, the device doesn't press, as before. This is backward compatible: an older device ignores the field and takes the photo without pressing, and an older controller never sends it.
- **The delay:** a controller sends the delay set for the device (ADR 0019). The device remembers the last delay a controller sent in `press`, `calib_photo` or `set_calib`, and uses it when calibrating on the device itself; until a controller has sent one, and with no broker set, it uses 3 s.
- **On the device itself,** the calibration photo now goes through the agent's job lock, so a press from a controller and a calibration photo can't overlap.

## Consequences
- **Each calibration photo uses up a code** on the display, and takes the delay longer.
- **A failed press** (Bot out of reach, battery) fails the photo with the press error. Untick the box to take the photo anyway.
- **The device's remembered delay is in memory only:** after the service restarts, calibration on the device waits 3 s until a controller presses again.

## Alternatives
- **A separate "Press" button on the calibration screen:** two taps per photo, and the user has to time the photo against the display going dark.
- **Store the delay on the device:** it is a controller setting (ADR 0019); keeping a second copy on the device would let the two disagree.
