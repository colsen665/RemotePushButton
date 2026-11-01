# TODO & decision log

Every decision has an ADR in [`docs/adr/`](docs/adr/). Every task names the ADR(s) it implements or depends on. When a decision changes, add a superseding ADR and update this file.

## Decisions
| # | Decision | Status |
|---|---|---|
| [0001](docs/adr/0001-record-architecture-decisions.md) | Record decisions as ADRs; `AGENTS.md` is the entry point, and `CLAUDE.md` imports it | Accepted |
| [0002](docs/adr/0002-old-android-phone-as-device.md) | A retired Android phone is the device (not ESP32 + home server) | Accepted |
| [0003](docs/adr/0003-switchbot-bot-over-direct-ble.md) | SwitchBot Bot over direct BLE presses the button (no hub or cloud) | Accepted |
| [0004](docs/adr/0004-no-vpn-e2e-encrypted-relay.md) | No VPN for controllers (phones allow only one VPN); E2E-encrypted relay instead of Tailscale | Accepted |
| [0005](docs/adr/0005-hivemq-cloud-mqtt-311-relay.md) | HiveMQ Cloud free tier, MQTT 3.1.1, broker-agnostic, 64 KB photo chunks | Superseded by 0011 |
| [0006](docs/adr/0006-e2e-crypto-and-qr-pairing.md) | XChaCha20-Poly1305 (Tink), HKDF per-direction keys, QR pairing, seq + ts replay guard | Accepted |
| [0007](docs/adr/0007-single-kotlin-app-two-modes.md) | One Kotlin app with Device and Controller modes over a pure-JVM `core` | Accepted |
| [0008](docs/adr/0008-template-based-seven-segment-reader.md) | On-device template-based 7-segment reader, no OCR library | Accepted |
| [0009](docs/adr/0009-device-app-as-home-launcher.md) | Device app is the HOME launcher (camera service rules); calibration uses still photos | Accepted |
| [0010](docs/adr/0010-text-first-photo-backup-copy.md) | Text first, photo as backup; in-app Copy marked as sensitive | Accepted |
| [0011](docs/adr/0011-emqx-cloud-serverless-relay.md) | EMQX Cloud Serverless free tier as the relay (HiveMQ retired Serverless); rest of 0005 unchanged | Accepted |
| [0012](docs/adr/0012-camera-open-only-during-jobs.md) | Camera opens when a job starts (settles during press + delay) and closes 10 s after the last capture | Accepted |
| [0013](docs/adr/0013-reader-alignment-search-and-refine.md) | Reader thresholds per digit cell, re-aligns each read (±5 px), rejects digits with ink beside an off segment; Refine fits the calibration given the code shown (amends 0008) | Accepted |
| [0014](docs/adr/0014-torch-light-and-fixed-photo-orientation.md) | Torch lights the display while the camera is open; fixed photo orientation plus a 90° rotation setting | Accepted |
| [0015](docs/adr/0015-app-pin-lock-with-optional-wipe.md) | Optional app PIN in both modes, asked on start and after 60 s away; optional wipe of all app data after 3 wrong PINs | Accepted |
| [0016](docs/adr/0016-locate-digits-from-the-code-and-remote-calibration.md) | Calibration finds the digit row, turn and polarity from the code shown; controllers calibrate remotely (`set_calib`, `calib`) (amends 0009, 0013, 0014) | Accepted |
| [0017](docs/adr/0017-read-from-a-full-size-crop-of-the-row.md) | The reader reads the calibrated row cut from the full-size camera image, turned upright, instead of the 1600 px photo (amends 0008) | Accepted |
| [0018](docs/adr/0018-follow-the-display-from-marks-around-the-row.md) | The device follows the display from the marks around the row (LCD frame, logo): an anchor made at the first valid read after calibrating, tiles matched in each photo, a shift, turn and scale fitted by consensus (amends 0017) | Accepted |
| [0019](docs/adr/0019-photo-delay-per-device-on-the-controller.md) | The wait between the press and the photo is 3 s by default, set per device on the controller; `press.delayMs` stays 1000 when absent | Accepted |
| [0020](docs/adr/0020-no-backup-transfer-or-screen-capture.md) | No cloud backup or device-to-device transfer of app data; `FLAG_SECURE` in both modes keeps codes and pairing QRs out of Recents and screenshots (amends 0006, 0015) | Accepted |
| [0021](docs/adr/0021-calibration-photos-can-press-first.md) | Calibration photos press the button and wait the set delay first, by default; optional `pressDelayMs` on `calib_photo` and `set_calib` (amends 0016) | Accepted |
| [0022](docs/adr/0022-anchor-the-display-when-calibrating-on-the-device.md) | A calibration saved on the device anchors the display on its own photo, so a move before the first press is followed (amends 0018) | Accepted |
| [0023](docs/adr/0023-optional-diagnostics-on-the-device.md) | Optional "Save diagnostics", off by default: full-size photos and job calibrations kept in app storage for adb, newest 40 files, deleted when turned off (amends 0020) | Accepted |
| [0024](docs/adr/0024-relay-hardening-and-release-builds.md) | Security review follow-up: random secret MQTT client IDs, broker authorisation rules, no codes in logcat, signed release builds on the phones with USB debugging off, PIN lockouts on the time since boot, rotate the controller credential after a lost phone (amends 0006, 0011, 0015, 0020, 0023) | Accepted |
| [0025](docs/adr/0025-optional-switchbot-bot-password.md) | Optional SwitchBot Bot password in the device settings, sent as its CRC32; stops casual presses in Bluetooth range but not a replay (amends 0003) | Accepted |
| [0026](docs/adr/0026-history-in-memory-expiring.md) | Controller history in memory only: each entry and the code on screen kept 5 min, all cleared when the PIN lock engages (amends 0010, 0015) | Accepted |
| [0027](docs/adr/0027-no-real-photos-or-codes-in-the-repo.md) | Tests run on drawn scenes with invented codes; no real photo of the display or code it has shown enters the repo | Accepted |

## Tasks
### Done
- [x] Core crypto: envelope, key derivation, pairing code, with tests (ADR 0006)
- [x] Protocol: CBOR messages, chunking, replay guard, with tests (ADRs 0005, 0006)
- [x] `MqttRelay` with re-subscribe on reconnect, plus an embedded-broker integration test (ADRs 0004, 0005)
- [x] `SevenSegReader`, with synthetic-display tests (ADR 0008)
- [x] `DeviceAgent` and `ControllerClient`: press → text → photo, busy, revoke, replay, online flag (ADRs 0007, 0010)
- [x] `AGENTS.md`, ADRs 0001–0010, `CLAUDE.md` → `AGENTS.md` (ADR 0001)

### Done, but not yet tested on hardware
- [x] Android app, Device mode: background service, BLE `BotClient`, `CameraCapture`, battery status (ADRs 0003, 0009)
- [x] Android app, Device mode screens: settings, pairing QR, controller list with revoke, Test press / Test photo, calibration (ADRs 0006, 0008, 0009)
- [x] Android app, Controller mode: QR scan, Press, code with Copy, photo, history (ADRs 0006, 0010)
- [x] Controller history in memory only, kept 5 min and cleared on lock; no codes or photos on disk (ADR 0026). Checked on a phone (2026-10-19)
- [x] `docs/protocol.md`, `docs/security.md`, `docs/setup.md`
- [x] `./gradlew :app:assembleDebug` builds and lint shows no errors (ADR 0007)
- [x] Controller warns when the device charges past the 80% limit or its battery is hot (ADR 0002)

### Bugs
- [x] The device went offline and stayed offline until the device app was restarted by hand (on the real rig). Cause: each status request read the Wi-Fi RSSI without `ACCESS_WIFI_STATE`, and the `SecurityException` crashed the app. Controllers ask for status whenever they connect. Android restarts a crashed service after 1 s, then 30 min, then 60 min. Fixed by declaring the permission and by `DeviceAgent` logging a failing command instead of letting it crash the app (ADRs 0009, 0011)
- [x] After a network loss the device never reconnected (airplane mode on the rig): every automatic reconnect was refused with `BAD_USER_NAME_OR_PASSWORD`. `MqttRelay` set the login and the last will on the first `connect()` only, and the HiveMQ client doesn't resend them on reconnects, so even with the login fixed the device would have had no last will after its first reconnect. A relay stopped while reconnecting also kept reconnecting in the background, under the same client ID as the new one. Both now live on the client, and `stop()` cancels reconnects; `MqttRelayReconnectTest` covers both (ADR 0011)
- [x] The device went offline about an hour into an overnight idle test and stayed offline (on the rig, running on battery). Cause: a phone vendor's battery-drain detection ("battery draining" in `dumpsys activity exit-info`) killed the app twice as the phone entered Doze. It ignores the battery-optimisation exemption, and it stops the foreground service, so Android doesn't restart it. Its notification is off by default, so the kill is silent. Fix: turn the detection off; setup §4 lists the setting. Confirmed in the 12 h idle test (next section): no kill in about 14 h on battery (ADRs 0002, 0009)
- [x] The controller never showed the Bot battery. `DeviceService` read it once at start and then every 6 h, and dropped a failed read without logging it. Fix: retry a failed read every 10 min, read again 15 s after a press when the last reading is older than 6 h, log each result, show it on the device screen. Confirmed on the rig: the device log shows "Bot battery 100%" and the controller "Bot 100%" (ADR 0003)
- [x] On the device phone, saving the settings froze the app until Android closed it as not responding. Cause: the phone couldn't reach the broker (a mistyped host, next entry), and while a HiveMQ client that has never connected waits to reconnect, `publish` blocks the calling thread. Save restarts the service, whose `onDestroy` publishes the offline flag on the main thread, and no coroutine timeout can interrupt that wait. `MqttRelay.publish` now fails until the first connection; `MqttRelayReconnectTest` covers it. Checked on the phone: two Saves with the broker unreachable restarted the service within 2 s, with no freeze (ADR 0011)
- [x] The device phone couldn't reach the EMQX deployment: `ConnectTimeoutException` on every connect. Cause: a typo in the host, a `-` for a `.` in `<id>.ala.<region>.emqxsl.com`. Every name under `emqxsl.com` resolves (to CloudFront), so the typo gave no DNS error, only a timeout on port 8883. Port tests with `toybox nc` misled at first: it exits 1 against a live MQTT port too, so it can't tell an open port from a dropped one. Setup now points at the host for this symptom (ADR 0011)
- [x] After a move to another device phone, presses failed after calibrating and Find digits failed from a controller. Found with a temporary build that saved each job's photos (see "Save diagnostics" under Next). Two causes:
  - **Reads:** after a calibration saved on the device itself, the digits sat about 12 px (0.15 digit heights) lower than calibrated in every photo, likely from tapping the phone (Save, the PIN). That is beyond the alignment search, so presses read `??????`. The anchor is only made at the first valid read, so there was none to follow the move. Fix: saving on the device anchors on the calibration photo (ADR 0022).
  - **Find digits:** in that phone's photos the digits fade towards the right end of the row. The display itself is even; the fade comes from where the torch sits relative to the display. The true row then scores about 0.66, below the locator's strong score, and in one photo the best start was tilted too steeply for Refine to recover. Fix: a tilted start that doesn't read is fitted again, made level. `SevenSegSceneTest` covers the fade with drawn scenes (ADR 0027). In one frame of three, the last 7 is too faint to read at all, so Find digits fails on that photo; Retake helps. Moving the phone so the torch lights the row more evenly may help too.
  - Confirmed on the rig: presses read after recalibrating. A press while the display still shows a code turns it off, so a quick second press photographs a blank display; that is the display's own behaviour, now in setup's troubleshooting (ADRs 0016, 0018, 0022)

### Next
- [x] Install the APK and smoke-test both modes on real phones (ADR 0007)
- [x] Tune the Bot mount and press on the real membrane button; fall back to a DIY servo if it's unreliable (ADR 0003)
- [x] Reader defaults tuned on real photos of the target display (gap 0.16, thickness 0.20) (ADR 0008); those photos have since left the repo (ADR 0027)
- [x] Photos covering every digit 0–9: four codes, three photos each (ADR 0008)
- [x] Reader re-aligns each read, thresholds per digit cell, and rejects dropped segments; Refine on the calibration screen; photos from the final rig in the tests (ADR 0013)
- [x] Torch instead of flash, fixed photo orientation, photo rotation setting (ADR 0014)
- [x] App PIN lock for both modes, with an optional wipe after 3 wrong PINs (ADR 0015)
- [x] Try the PIN lock and the wipe on a phone: lock after 60 s away, wipe returns to mode selection, the device service stops (ADR 0015)
- [x] Locate the digit row from the code shown; remote calibration from a controller (ADR 0016)
- [x] Photo from the second rig position (read upside down at first): the locator settles the orientation first (the code against its 180° turn), and Refine moves edges to fit a tilted row (ADR 0016)
- [x] Calibrate the mounted display from a controller with Find digits; note how long it takes on the phones (ADRs 0014, 0016)
- [x] Read from a full-size crop of the digit row (ADR 0017)
- [x] Check on the device phone that reads use the row crop: no "row crop" fallback lines in the device log (ADR 0017)
- [x] Follow the display from the marks around the row, so a bump doesn't need a new calibration (ADR 0018)
- [x] Wait 3 s before the photo by default, set per device on the controller (ADR 0019)
- [x] On the device phone: after calibrating, the log shows "display anchored"; nudge the display, and the next press logs "display followed" and reads correctly (ADR 0018)
- [x] Check the torch glare on the rig; tilt the light or camera if a digit washes out (ADR 0014)
- [x] Calibration screen: while it takes a photo, finds the digits, saves or refines, cover it with an overlay showing a spinner and the step. The step is now a line of text at the top of the screen, out of view when scrolled down to **Find digits** or **Save**, so it isn't clear that anything is happening (ADR 0016)
- [x] Calibration screen: "Press the button first, then wait N s", on by default, for calibration photos on the device and from a controller; checked on the rig (ADR 0021)
- [x] Move the device to another phone. Stop the app on the old phone first: the Bot takes one connection at a time. Then setup §4 on the new phone, including its battery and charging settings; pair the controllers again, since keys don't transfer between phones (ADR 0020); calibrate. Check again on the new phone: focus at the rig distance, torch glare, the Bot connecting without a scan, the camera reopening with the screen off (ADRs 0002, 0003, 0006, 0012, 0014, 0020)
- [x] Device settings: a "Save diagnostics" checkbox, off by default. When on, each job saves its full-size camera JPEG and the calibration it read with to app-private storage, for pulling with `adb shell run-as` and reproducing reads in `core`; turning it off deletes them. The photos show codes, so they never leave the phone by any other route (ADRs 0017, 0020, 0023)
- [x] Phone hardening: factory reset, battery optimisation exemption, vendor battery-drain detection off, RemotePushButton as the Home app, automatic time, charge limit or smart plug (ADRs 0002, 0009)
- [x] Security review (2026-10-19): the crypto, replay guard, PIN, Keystore storage, backup exclusion and git history checked and sound. Fixed: the device logged each read code to logcat; the device's MQTT client ID was derived from the device ID in every QR, so a lost or revoked controller phone could take its session and keep it offline; PIN lockouts ran on the wall clock. Documented and in setup: EMQX authorisation rules, release builds instead of `app-debug.apk` (which lets `adb shell run-as` read the history and decrypt the secrets), USB debugging off on the device phone, credential rotation after a lost phone, the Bot's unauthenticated BLE press in the threat model (ADR 0024)
- [x] Install the release build on both phones (uninstall the debug build first, pair again), set the EMQX authorisation rules, and turn USB debugging off on the device phone (ADR 0024; `docs/setup.md` §1–2, §4 step 9). Done 2026-10-19. The rules were checked by removing the device's subscribe rule: the device logged the failed subscription and a press timed out; with the rule back, presses read again
- [x] 12 h idle test: left idle, the device stays online with no kills in `dumpsys activity exit-info`, the battery temperature stays normal, and idle data use stays small; a press at the end reads. There is no soak test: the device isn't tested under many presses in a row. Result, about 14 h on battery: the app stayed one process with no new `exit-info` entry; the relay reconnected within 1.5 s after each drop; the Bot battery read every 6 h; the phone used about 2% battery an hour; the app used about 0.4 MB a day, some 12 MB of the 1 GB monthly quota; the press at the end read correctly after following the display (ADRs 0002, 0011)
- [x] Second security review (2026-10-19, after ADRs 0024–0026): no finding. Two independent reads of `core` (crypto, protocol, relay, PIN lock, device agent, controller client) and the app adapters (storage, Keystore, PIN UI, service, BLE, screens, manifest, backup rules). Checked and sound: the envelope's nonce, AAD and per-direction keys; the replay guard; pairing-code validation, including a hostile QR, which gets its own relay and key; the PIN lock across activity recreation, process death and the notification intent; TLS hostname verification, confirmed in the HiveMQ 1.3.3 jar (`setEndpointIdentificationAlgorithm("HTTPS")` by default); every string reaching logcat; the controller-supplied job ID in diagnostics file names (can't leave the directory: the `<ms>-job-` prefix never exists as a directory). Not reported: the known limitations in `docs/security.md`, denial of service (`ChunkAssembler` allocates `n` slots from the authenticated device), and the loss of an `apply()`-written seq on a crash right after accepting a command

### Known risks to check on hardware
- [x] `BotClient` connects by MAC address without a BLE scan. Some phones fail on uncached devices with GATT status 133; if so, add a short scan before connecting, which needs `BLUETOOTH_SCAN` and, on Android 11 and below, location (ADR 0003)
- [x] ~~`CameraCapture` keeps the camera bound all the time~~: it now opens only during jobs (ADR 0012)
- [x] The camera reopens from the background service with the screen off, and the first photo after reopening is properly exposed (ADRs 0009, 0012). Checked on the rig with the screen off for 3 min: two presses, each reopening the camera; a failed read on the first came from the mount, not the camera
- [x] EMQX Serverless: the retained `online` flag and last will work as with Moquette (ADR 0011). Checked on the rig: the retained flag reaches a newly connected controller; a clean stop, a killed app and a network loss show offline; the device comes back online after the network returns. TLS is checked: the server sends the full chain to DigiCert Global Root G2, which is in the system trust store, so the downloaded certificate isn't needed
- [ ] Changing device settings restarts the service. A press arriving during the restart gets an ack timeout on the controller (ADR 0009)
- [ ] `RelayIntegrationTest` is flaky: in about one run in three, a different test times out after 10 s (seen with "revoked controller is ignored", "press returns text first…" and "device going away clears the online flag"). It predates the reader and PIN work; probably timing in the embedded broker setup (ADR 0011)

### Later / ideas
- [ ] Report how far the display has been followed, and the reader's alignment shift, to the controller, to warn of mount drift before it leaves the range (ADRs 0013, 0018)
- [ ] `SevenSegLocator` can miss a drawn scene after a shift of 3 px or a small change of blur, though the reader reads it at the true calibration. Find out whether real rigs see this too (a Retake helps); the coarse scan may be stepping over thin strokes (ADR 0027)
- [ ] Smaller photo to stretch the 1 GB/month EMQX quota: crop to the calibrated digit box plus a margin, or lower `MAX_SIDE` (ADRs 0010, 0011)
- [ ] Upgrade dependencies flagged by lint (AGP 9.x, Compose BOM, CameraX 1.6, lifecycle 2.11) (ADR 0007)
- [x] Support a SwitchBot Bot password (ADRs 0003, 0025). Checked on a phone (2026-10-19)
- [ ] Show "Bot password wrong or missing" instead of the bare status code; needs the status the Bot returns for a wrong password, not yet recorded (ADR 0025)
- [ ] Forward secrecy with ephemeral X25519 session keys (ADR 0006 limitation; needs a new ADR)
- [ ] R8/minify keep rules for Netty, Tink and serialization before a release build (ADR 0007)
- [ ] iPhone controller (KMP or Flutter) against `docs/protocol.md` (ADR 0007)
- [ ] Quick Settings tile "Press & copy" reusing `ControllerClient.press()` (ADR 0010); must respect the PIN lock (ADR 0015)
- [x] `FLAG_SECURE` so Recents doesn't show the last code; set in both modes, which covers the pairing QR too (ADRs 0015, 0020)
- [x] Add `android:dataExtractionRules` excluding everything: `allowBackup="false"` doesn't stop device-to-device transfer on Android 12+ (lint `DataExtractionRules`), although `docs/security.md` says backup is disabled (ADRs 0006, 0020)

## Open questions / human tasks
These need the user; agents can't do them.
- [x] Device phone model and Android version; this sets `minSdk`, currently 26, which stays, for older controller phones (ADR 0002)
- [x] Create an EMQX Cloud Serverless deployment (spending limit 0) with two credentials, `rpb-device` and `rpb-controllers`; note the host (ADR 0011; `docs/setup.md` §1)
- [x] Make a release signing key and add the `rpb.*` lines to `android/local.properties` (ADR 0024; `docs/setup.md` §2). Done 2026-10-19; `assembleRelease` produces a signed `app-release.apk`
- [x] The SwitchBot Bot's MAC address, with the Bot set to Press mode (ADR 0003; `docs/setup.md` §3)
- [x] Connect a phone over `adb` (USB debugging on) for the first on-device test (ADR 0007)
- [x] A sample photo of the target display, to tune reader defaults (ADR 0008). Twelve hand-held photos of four codes, not kept in the repo (ADR 0027)
