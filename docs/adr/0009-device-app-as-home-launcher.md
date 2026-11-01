# 9. The device app registers as the home launcher, and calibration uses still photos

- Status: Accepted; amended by [0016](0016-locate-digits-from-the-code-and-remote-calibration.md) (calibration also runs remotely from controllers)
- Date: 2026-10-15

## Context
From Android 11, a background service may use the camera only if it was started while the app was on screen. Android 14 also forbids starting camera services from the boot-completed broadcast. The device must recover on its own after a reboot.

## Decision
- **Home launcher:** in Device mode the app enables an activity alias that registers it as a **HOME launcher**. After every boot the system opens the app on screen, and the app starts its background service from there.
- **Service types:** the service declares the `camera|connectedDevice` types and holds a partial wake lock and a Wi-Fi lock. The user is asked to exempt the app from battery optimisation.
- **Camera ownership:** the service owns the camera, a CameraX `ImageCapture` tied to its lifecycle. The calibration screen therefore works on **still photos** taken through the service, with a "retake" button, rather than a live preview. This avoids two lifecycles competing for the camera.

## Consequences
- **Device-only:** the device phone should run nothing else. The launcher alias is disabled again when switching to Controller mode.
- **Calibration without live feedback:** it's slightly less immediate, but deterministic, and it uses exactly the frames the reader will see.
