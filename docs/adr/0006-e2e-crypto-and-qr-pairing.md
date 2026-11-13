# 6. End-to-end crypto: XChaCha20-Poly1305 (Tink), per-direction keys, QR pairing

- Status: Accepted; amended by [0024](0024-relay-hardening-and-release-builds.md) (revoking a lost phone also rotates the controller credential), [0028](0028-keystore-key-bound-to-the-screen-lock.md) (the Keystore key needs the screen lock), [0029](0029-strongbox-where-available.md) (StrongBox where available), [0030](0030-forward-secrecy-for-job-events.md) (per-job keys for events) and [0033](0033-controllers-bound-to-a-hardware-key.md) (signed commands and events, pairing code version 2)
- Date: 2026-10-15

## Context
The relay is untrusted (ADR 0004). We need confidentiality, authenticity and replay protection, plus a pairing flow simple enough for non-experts.

## Decision
- **Pairing:** the device generates a 16-byte `controllerId` and a 32-byte master key `K` for each controller, and shows them as a QR code. The code is `rpb1:` followed by base64url-encoded CBOR containing the device ID, controller ID, key, broker settings and device name.
  - Scanning the QR code is the authorisation: only someone physically at the device can pair.
  - Revoking a controller deletes its entry on the device.
- **Key derivation:** HKDF-SHA256(K, salt = deviceId‖controllerId) produces one key per direction: `info = "rpb1 cmd"` and `"rpb1 evt"`.
- **Message format:** `ver(1) | nonce(24) | ciphertext | tag(16)`, using XChaCha20-Poly1305 with random nonces.
  - The additional authenticated data is `ver‖deviceId‖controllerId‖direction`, which blocks cross-pair replay and reflecting a message back the other way.
- **Replay protection:** every command carries `seq` and `ts`.
  - `seq` must be strictly greater than the last accepted value for that controller.
  - `ts` must be within ±120 s of the device clock.
  - Controllers generate `seq` as max(last + 1, now in ms), so it keeps increasing even if local state is lost.
- **Library:** **Google Tink** (pure Java, works on the JVM and Android). The plan named libsodium/lazysodium; Tink was chosen instead because its pure-Java implementation lets the crypto be tested directly on the JVM with no native libraries. The algorithms are standard, so a future iOS controller can use libsodium's `crypto_aead_xchacha20poly1305_ietf` plus any HKDF-SHA256 implementation.

## Consequences
- **No forward secrecy:** the master key is static, so leaking it exposes recorded traffic. A future improvement is ephemeral X25519 session keys.
- **Keystore wrapping:** keys must be stored encrypted with the Android Keystore on both phones.
- **Time sync:** both phones must use automatic network time.

## Alternatives
- **libsodium (lazysodium):** needs native libraries on the JVM for tests.
- **Noise or TLS-in-MQTT:** heavier than this use case needs.
