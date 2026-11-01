# 15. App PIN lock, with an optional wipe after three wrong PINs

- Status: Accepted; amended by [0020](0020-no-backup-transfer-or-screen-capture.md) (`FLAG_SECURE` covers the Recents thumbnail) [0024](0024-relay-hardening-and-release-builds.md) (lockouts on the time since boot; release builds keep `run-as` out) and [0026](0026-history-in-memory-expiring.md) (locking clears the controller history)
- Date: 2026-10-16
- Refines: [0006](0006-e2e-crypto-and-qr-pairing.md) (keys at rest), [0009](0009-device-app-as-home-launcher.md) (device phone UI)

## Context
Whoever can open the app on a paired controller can press and get a fresh code. On the unattended device phone, whoever can open the app can show a pairing QR (ADR 0006), change the broker settings or revoke controllers. Until now the only protection was the phone's own screen lock. A controller phone may be lent out unlocked, and the device phone sits unattended in a rig.

## Decision
- **PIN for both modes:** 4–8 digits, optional, set under *PIN lock* (on the device's home screen, or in the controller's PIN lock dialog).
- **When the PIN is asked:** when the app starts, and when it returns after more than 60 s in the background. A rotation doesn't count as leaving. While locked, the app shows only the PIN prompt. The device service keeps running, so remote presses still work.
- **Storage:** a salted PBKDF2-HMAC-SHA256 hash (120,000 iterations) and an attempt counter, kept in the Keystore-encrypted secrets blob. A failure is counted before the PIN is checked, so killing the app mid-check doesn't reset the counter. Changing or removing the PIN, or the wipe option, needs the current PIN, and a wrong one there counts as a failed attempt.
- **Wrong PINs, wipe on:** after 3 wrong PINs in a row, the app erases all its data and restarts at mode selection. Erased are the secrets (pairing keys, controllers, broker credentials, calibration, the PIN), the preferences (mode, replay counters), the controller history (codes and photos) and the Keystore key. The process exits, so nothing decrypted stays in memory. On the device phone, the HOME launcher alias is switched off too.
- **Wrong PINs, wipe off:** from the third wrong PIN on, each attempt waits 30 s, doubling up to 1 h.
- **Logic in `core`:** `PinLock` holds the logic with unit tests; the app adds storage, the prompt and the wipe.

## Consequences
- **A borrowed or lost unlocked controller can't press** without the PIN. On the device phone, pairing QRs and settings are behind the PIN.
- **Wipe is destructive by design:** a wipe on the device phone stops the service, and the device stays offline until it is set up and paired again. That's why wipe is off by default and explained where it's switched on.
- **The hash alone doesn't protect the PIN:** a PIN has at most 10⁸ values, so offline guessing is cheap for someone who can read the decrypted blob. The real protection is the Keystore encryption of the blob plus the attempt limit.
- **Lockouts used the wall clock,** which someone holding the unlocked phone could set forward. Since ADR 0024 they run on the time since boot, and a reboot restarts the wait. With wipe on, the attempt limit never depended on the clock.
- **Not covered:** the Recents screen may show a thumbnail of the last code. The app doesn't set `FLAG_SECURE`.
