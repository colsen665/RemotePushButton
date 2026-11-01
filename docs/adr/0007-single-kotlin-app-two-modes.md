# 7. One Kotlin Android app with Device and Controller modes, over a pure-JVM core

- Status: Accepted
- Date: 2026-10-15

## Context
Both ends are Android for now. They share the crypto, protocol and relay code. We want most of the logic testable without a phone or emulator.

## Decision
- **`android/core`** is a Kotlin/JVM library containing the crypto, protocol, relay, 7-segment reader and the two protocol state machines: `DeviceAgent` for the device side and `ControllerClient` for the controller side.
  - Hardware is reached through small interfaces: `Presser`, `FrameSource` and `StatusSource`.
- **`android/app`** is a single APK. On first run the user picks **Device** or **Controller** mode.
  - The Android-specific code stays thin: BLE, CameraX, the background service, Keystore storage, and the Jetpack Compose UI.
- **Prototype:** we skipped the Termux prototype, because Termux can't use BLE (ADR 0003).

## Consequences
- **Testing on the JVM:** `./gradlew :core:test` covers almost all behaviour, including an end-to-end press → text → photo exchange through an embedded broker.
- **Future iPhone controller:** would need Kotlin Multiplatform or Flutter, re-implementing `ControllerClient` against `docs/protocol.md`.
