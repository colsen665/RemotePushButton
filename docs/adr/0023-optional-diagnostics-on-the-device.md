# 23. Optional diagnostics: the device keeps full-size photos for adb

- Status: Accepted; amended by [0024](0024-relay-hardening-and-release-builds.md) (the debug build is installed only while diagnosing)
- Date: 2026-10-18
- Amends: [0020](0020-no-backup-transfer-or-screen-capture.md) (where photos may be stored)

## Context
- **Failed reads need the real photos.** In ADR 0022's case the cause of the failed presses was only found by pulling each job's full-size photo and the saved calibration off the phone, and reading them again in `core`. That took a temporary build.
- **The app has no way to export photos:** no sharing, and `FLAG_SECURE` blocks screenshots (ADR 0020). The calibration sits in the Keystore-encrypted settings.
- **The photos show codes.** Until now the device kept photos in memory only.

## Decision
- **Setting:** "Save diagnostics", a checkbox in the device's Settings, off by default. It takes effect with **Save**, like the other settings.
- **What is saved, while it is on** (`Diagnostics`, in `core`):
  - every camera photo, full size and as the camera made it: `<ms>-photo-t<turn>.jpg`, where turning it `<turn>` degrees clockwise makes it upright;
  - after each job, the calibration and the display anchor the device held: `<ms>-job-<job>.json`. It holds no code.
- **Where:** `files/diagnostics/` in app-private storage. On a debuggable build, `adb shell run-as net.rpb.app` reads it. It is never sent to a controller or onto the relay, and it is excluded from backup and transfer like all app data (ADR 0020).
- **Size:** only the newest 40 files are kept, about ten jobs and some 100 MB with a 16 MP camera.
- **Off deletes:** saving the settings with the box unticked deletes the folder. A PIN wipe deletes it with all app data (ADR 0015).
- **Failures:** a write that fails is dropped and never fails a job.

## Consequences
- **Photos of codes stay on the phone while it is on.** Anyone who can run `adb` on the unlocked device phone can read them. The codes are one-time codes and expire quickly, but the setting should be on only while investigating.
- **A release build can't be read with `run-as`.** Since ADR 0024 the phones run release builds; to pull diagnostics, install the debug build on the device phone for the investigation and the release build again afterwards.
- **Storage:** up to about 100 MB while on.
- **Calibration photos taken on the device itself are saved too,** since they come from the same camera.
