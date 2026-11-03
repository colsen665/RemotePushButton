# 29. The Keystore key lives in StrongBox where the phone has one

- Status: Accepted
- Date: 2026-11-03
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (keys at rest), [0028](0028-keystore-key-bound-to-the-screen-lock.md) (how the key is created)

## Context
The Keystore key that wraps the data key (ADR 0028) normally lives in the TEE, the secure mode of the main processor. TEE exploits have been found on most vendors' chips and are how a rooted phone's "hardware-backed" keys have been stolen in the past. Since Android 9 some phones also have **StrongBox**: a separate secure element with its own CPU, memory and storage, which such exploits don't reach. It is slower and supports fewer algorithms, but AES-256-GCM, which the wrap uses, is among them.

## Decision
- **Ask for StrongBox** with `setIsStrongBoxBacked(true)` when the phone reports `FEATURE_STRONGBOX_KEYSTORE`; on `StrongBoxUnavailableException`, create the key in the TEE instead. The screen-lock binding of ADR 0028 applies either way.
- **The key is used once per process**, to unwrap the data key (ADR 0028), so StrongBox's speed doesn't matter.
- **Chosen once.** The backing is decided when the key is created, at setup or at the ADR 0028 migration. A phone that gains StrongBox with a system update keeps its TEE key until the app is set up again; no second migration.
- **Shown to the user.** The PIN lock dialog shows *Key storage: StrongBox / hardware / software*, from `KeyInfo.getSecurityLevel()` on API 31 and later and `isInsideSecureHardware` before.

## Consequences
- **Controller phones from about 2018 on** (Pixel 3 and later, most Samsung flagships) keep the wrapping key out of the TEE. A thief then needs the screen-lock credential or a secure-element exploit, which is a far rarer thing.
- **The old device phone** most likely has no StrongBox and stays with the TEE. Its blob holds every controller's key, so it remains the weaker of the two; the screen lock (ADR 0028) is what protects it.
- **No change on disk:** the blob format is ADR 0028's, whatever backs the key.

## Alternatives
- **AndroidX Security's `MasterKey` with `setRequestStrongBoxBacked`:** the library is deprecated, and it would replace a wrapper that is thirty lines.
- **StrongBox for the data key too:** it would mean a StrongBox round trip on every save; the data key is a software key by design (ADR 0028).
