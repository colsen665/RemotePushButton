# 20. No backup, device transfer or screen capture of the app

- Status: Accepted; amended by [0024](0024-relay-hardening-and-release-builds.md) (release builds keep `run-as` away from the history)
- Date: 2026-10-17
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (keys at rest), [0015](0015-app-pin-lock-with-optional-wipe.md) (the Recents thumbnail it left uncovered)

## Context
- **Device-to-device transfer:** `android:allowBackup="false"` stops all backups only up to Android 11. From Android 12 it covers only cloud backup, so setting up a new phone from an old one copies the app's data across. That data includes the controller history (codes and photos), the preferences and the Keystore-encrypted secrets blob. The blob can't be decrypted on the new phone, since the Keystore key stays behind, but the history is not encrypted. `docs/security.md` said "App backup is disabled", which no longer held on newer phones.
- **Recents and screenshots:** Android keeps a thumbnail of the last screen for Recents. On a controller that is the last code, and on the device phone it can be a pairing QR, which pairs a new controller until revoked (ADR 0006). The PIN lock (ADR 0015) hides the app after 60 s away, but not the thumbnail taken as the app goes to the background.

## Decision
- **Exclude everything from backup and transfer:** `android:dataExtractionRules` excludes every domain from both cloud backup and device-to-device transfer (Android 12+). `android:fullBackupContent` excludes the same for Android 11 and below, where `allowBackup="false"` already stops backups. `allowBackup="false"` stays.
- **`FLAG_SECURE` on the activity, in both modes:** Recents shows a blank thumbnail, and screenshots and screen recordings of the app come out black. Compose dialogs inherit the flag, so the copy confirmation, the photo viewer and the pairing QR are covered too.

## Consequences
- **A new phone starts empty:** after a transfer, the app starts at mode selection and has to be paired again. With the secrets blob undecryptable there, that was effectively already the case.
- **No screenshots for bug reports:** to show a problem on screen, photograph the phone with another one.
- **Screen sharing and casting** show the app as black.
- **Not covered:** someone looking at the screen, or photographing it, while a code or QR is shown.

## Alternatives
- **`FLAG_SECURE` only in Controller mode, or only on the code and QR screens:** keeps screenshots of settings and calibration, but the flag would have to follow every screen that shows a code or a QR, and the device's pairing QR is at least as sensitive as a code.
- **Rely on `allowBackup="false"`:** leaves device-to-device transfer open on Android 12+.
- **Encrypt the history with the Keystore key:** protects it in a transfer and on a rooted phone too, but doesn't address Recents, and is a larger change. It can still come later.
