# RemotePushButton: agent guide

You tap **Press** in the app on your phone, from anywhere. A retired Android phone at the remote site then:
1. has a **SwitchBot Bot** press a membrane button,
2. waits 3 s (set per device on the controller),
3. photographs a **7-segment display**, lit by its own torch (the rig keeps the display dark),
4. reads the digit code,
5. and sends back the **text first** and the **photo as a backup**.

The controller app shows the code with a **Copy** button. All traffic goes through an untrusted MQTT relay and is **end-to-end encrypted** in the app, so controllers need no VPN. Both modes can be locked with an optional PIN.

## Architecture
```
Controller phone (RPB app, Controller mode)            any network, any VPN
   │  MQTT 3.1.1 over TLS; payloads E2E encrypted (XChaCha20-Poly1305)
   ▼
EMQX Cloud Serverless (untrusted relay)
   ▲
   │  outbound MQTT over TLS
Device phone (old Android, RPB app, Device mode — HOME launcher + foreground service)
   ├─ CameraX ImageCapture ──► SevenSegReader (on-device)
   └─ BLE GATT ──► SwitchBot Bot ──► membrane button
```
Protocol: [`docs/protocol.md`](docs/protocol.md). Threat model: [`docs/security.md`](docs/security.md). Setup: [`docs/setup.md`](docs/setup.md).

## Repo layout
```
android/                Gradle root (Kotlin 2.0, AGP 8.7, compileSdk 35, minSdk 26)
  core/                 pure Kotlin/JVM — crypto/, protocol/, relay/, ocr/, lock/PinLock, device/DeviceAgent, controller/ControllerClient
  app/                  Android app — device/ (service, BLE, camera, pairing, calibration), controller/ (UI), common/ (storage, PIN lock UI)
docs/                   protocol.md, security.md, setup.md, adr/
hardware/mount/         (planned) printable stand / Bot bracket, see README there
```

## Build & test
This needs JDK 21 (Temurin), the Android SDK, and `adb` on `PATH`. Where they are on this machine is in the gitignored `CLAUDE.local.md`; if that file or a value is missing, ask the user and store the answer there.
```sh
cd android
./gradlew :core:test          # unit + embedded-broker integration tests (no device needed)
./gradlew :app:assembleDebug  # APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # what the phones run (ADR 0024); needs the rpb.* signing keys in local.properties, see docs/setup.md §2
adb install -r app/build/outputs/apk/release/app-release.apk
```
A debug build can't update a release install or the other way round: `adb uninstall net.rpb.app` first, which erases the app's data.
`android/local.properties`, which is gitignored, points at the Android SDK. On a fresh checkout, recreate it with `echo "sdk.dir=<Android SDK path>" > android/local.properties`.

Check for an attached phone with `adb devices`.

`RelayIntegrationTest` is flaky: in about one run in three, one of its tests times out. Rerun it before suspecting a change (tracked in `TODO.md`).

CI (`.github/workflows/ci.yml`) runs `:core:test` and `:app:assembleDebug` on pushes to `master` and on pull requests. `.github/scripts/core-test.sh` retries `RelayIntegrationTest` up to twice; a failure in any other test fails the build at once.

### Reader test scenes
The reader, locator and anchor tests run on `Scenes` in `core/src/test/.../SyntheticPhoto.kt`: drawn displays with invented codes (ADR 0027). **Never commit a real photo of the display or a code it has shown**, in a file, a filename, a test, a doc or a commit message. To reproduce a failed read, add a scene with an invented code that shows the same problem (fade, glare, tilt, blur). The locator is sensitive to small changes in a scene (tracked in `TODO.md`), so a new scene may need a nudge before it is found.

## Conventions
- **Logic goes in `core`, with tests.** The Android code stays a thin adapter: BLE, CameraX, Keystore, background service, UI. Hardware is reached through `Presser`, `FrameSource` and `StatusSource`.
- **The relay is untrusted.**
  - Never put plaintext, codes, keys or photos on a topic.
  - Never log keys, codes or decrypted payloads.
  - Topics contain only random IDs.
- **Codes and photos of the display stay out of the repo** (ADR 0027).
- **Protocol changes:**
  - keep CBOR fields backward compatible (decoders use `ignoreUnknownKeys`, new fields get defaults);
  - bump the envelope or pairing version for breaking changes;
  - update `docs/protocol.md`.
- **Decisions:** any change to the architecture, protocol or security model gets a new ADR in `docs/adr/`, or supersedes an old one, and a row in the decision table in `TODO.md`.
- **Intentional lint warnings in `:app`; leave them alone:**
  - `BatteryLife`: the battery-optimisation request; the app isn't distributed through Play.
  - `WakelockTimeout`: the device must stay awake.
  - `StaticFieldLeak` in `DeviceRuntime`: the service clears it in `onDestroy`.
  - Dependency-version warnings: the upgrade is tracked in `TODO.md`.
- **Code style:** match the surrounding code, keep comments sparse and only where the reason isn't obvious, and follow Kotlin official code style.

## Decisions & tasks
- **Decision log, task list and open questions:** [`TODO.md`](TODO.md). Every entry links to its ADR in [`docs/adr/`](docs/adr/).
- **Before changing the architecture, protocol or security model:** read the relevant ADR.
- **When a task is finished:** tick it off in `TODO.md`.
