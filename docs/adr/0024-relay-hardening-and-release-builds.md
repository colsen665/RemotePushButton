# 24. Relay hardening and release builds: random client IDs, broker ACLs, no codes in logcat, signed APKs

- Status: Accepted
- Date: 2026-10-19
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (what revoking covers), [0011](0011-emqx-cloud-serverless-relay.md) (broker setup), [0015](0015-app-pin-lock-with-optional-wipe.md) (lockout clock, what the PIN protects against), [0020](0020-no-backup-transfer-or-screen-capture.md) and [0023](0023-optional-diagnostics-on-the-device.md) (debuggable builds)

## Context
A security review of the code and the setup (2026-10-19) found the crypto and the protocol sound, and four gaps around them:
- **Codes in logcat.** The device logged each job's read code through `android.util.Log`, against the rule in `AGENTS.md`, and the setup told the user to keep hours of logcat.
- **Debug builds.** Both phones ran `app-debug.apk`. On a debuggable package, `adb shell run-as net.rpb.app` reads the controller history (codes and photos) and the diagnostics, and runs code as the app's UID, which the Keystore key is bound to. With USB debugging on, that reads every secret without the PIN. The device phone has no system screen lock to gate the adb authorisation.
- **Guessable client IDs.** The device connected as `rpb-dev-<deviceId prefix>`. The device ID is in every pairing QR and every topic, and a broker drops the existing session when another client connects with the same ID. Revoking a controller deletes only its key; the shared controller password stays valid in a lost phone, so a revoked controller could keep the device offline at no cost. `docs/security.md` listed only "flood the broker" for that party.
- **No broker authorisation.** EMQX allows every authenticated client to publish and subscribe anywhere. The controller credential could subscribe to all topics and publish a forged retained `online` flag.
- **Lockouts on the wall clock** (noted in ADR 0015): with wipe off, setting the clock forward skipped the waits.

## Decision
- **No codes in the log.** The device logs a job's outcome (ok, invalid with its confidence, or the error), never the code. The screen's "Last job" line still shows it: physical access to the device phone is trusted (ADR 0015).
- **Random client IDs.** The device connects as `rpb-dev-` plus a random 8-byte `relayId` kept in its encrypted settings and never shared; a controller as `rpb-ctl-` plus 8 random bytes per connection (sessions are clean, so a stable ID buys nothing). Neither can be derived from a QR or a topic.
- **Broker ACLs in setup.** EMQX authorisation, default deny, with rules per credential: the device may publish `rpb/+/online` and `rpb/+/c/+/evt` and subscribe to `rpb/+/c/+/cmd`; controllers may subscribe to `rpb/+/online` and `rpb/+/c/+/evt` and publish to `rpb/+/c/+/cmd`.
- **Revoking means rotating.** After revoking a controller for a lost phone, change the controller password on EMQX and pair the remaining controllers again; the old password stays in the lost phone.
- **Release builds.** `assembleRelease`, signed with a key named in `local.properties`, is the normal install on every phone. R8 stays off, so no keep rules are needed. The debug build goes on the device phone only while pulling diagnostics (ADR 0023), and USB debugging is turned off on the device phone once it is set up.
- **Lockouts on the time since boot.** `PinLock` measures waits on a clock the app passes as `SystemClock.elapsedRealtime()`. A clock that went backwards (a reboot) restarts the full wait.

## Consequences
- **A lost controller phone with USB debugging on no longer gives up its secrets** to someone with a cable; the PIN and the Keystore do what ADR 0015 promised. A rooted phone still reads everything, as before.
- **Diagnostics need a debug build on the device phone**, installed for the investigation and replaced by the release build afterwards. `adb install -r` across build types fails on the signature: uninstall first, which erases the app data, so pair again.
- **The `rpb-controllers` password still gates nothing but broker access.** Anyone holding it can still flood the broker (denial of service, as before), but can't take the device's session or forge its presence.
- **Revoking is now two steps** (device screen, then EMQX) when the phone is lost rather than retired.
- **A reboot adds a full wait** for a user who is locked out with wipe off.

## Alternatives
- **Per-controller broker credentials:** revoking would then revoke broker access too, but EMQX Serverless credentials are managed by hand on the console, and pairing would need a console step for every controller.
- **Encrypted history on the controller:** still worth doing (ADR 0020), but a debuggable build hands out the Keystore key's use along with the files, so it wouldn't have helped here.
- **Keep debug builds and rely on the phones' screen locks:** the device phone runs without one, and the point of the PIN is a lent or lost unlocked phone.
