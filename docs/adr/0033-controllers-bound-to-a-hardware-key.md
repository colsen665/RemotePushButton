# 33. Each pairing is bound to a signing key in the controller phone's Keystore

- Status: Proposed
- Date: 2026-11-12
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (pairing, command envelope), [0028](0028-keystore-key-bound-to-the-screen-lock.md) (keys at rest), [0030](0030-forward-secrecy-for-job-events.md) (which events a controller takes in version 1)

## Context
Whoever holds a pairing's master key `K` can press as that controller until it is revoked on the device. `K` can leak in three ways: a photographed pairing QR (a known limitation in `docs/security.md`), a blob copied from a phone with its screen-lock credential or an exploit of the secure hardware (ADR 0028), or the memory of a controller with the app open. In each case the thief can press from any phone, anywhere.

A FIDO security key or a device-bound passkey solves the same problem: a private key that can't leave the phone's secure hardware, a public key on the other side, and a signature on every request. Here the controller phone can play that role, so a pairing works from that one install only.

## Decision
- **One signing key per pairing on the controller:** EC P-256 with SHA-256 in the Android Keystore, purpose sign only, not exportable. It is created in StrongBox where the phone has one and in the TEE otherwise (ADR 0029), with the alias `rpb-sig-<deviceId>-<controllerId>`.
  - **It needs the screen lock or a strong biometric within the last 5 min:** `setUserAuthenticationParameters(300, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)` on API 30 and later, `setUserAuthenticationValidityDurationSeconds(300)` before, and `setInvalidatedByBiometricEnrollment(false)`. A press after the window shows the screen-lock prompt first, then goes through.
  - Removing a pairing deletes its key, and so does a wipe of the app.
- **Bind, the first command after the scan:** `bind {seq, ts, job, epk, pub}`, where `pub` is the X.509 SubjectPublicKeyInfo of the new key. The command is signed by the key it carries, which proves the controller holds it. The device answers `bound {job, fingerprint}` in the job envelope (ADR 0030), so someone holding `K` can't forge the reply.
  - **Fingerprint:** the first 8 bytes of SHA-256 of `pub`, written as four groups of four hex characters. Both phones show it so the user can compare them.
- **The first bind wins, and only while the QR is shown.** A new pairing is pending, with a deadline 10 min after the QR appears. The device accepts one `bind` while the QR dialog is open and the deadline hasn't passed. Closing the dialog or reaching the deadline before a bind removes the pairing. Once bound, the QR is worthless.
- **Signed command envelope, version 3** (sign, then encrypt, so the relay never sees the signature, and only a `K` holder reaches the signature check):
  ```
  sig       = ECDSA-P256-SHA256(sk_ctl, "rpb3 cmd" ‖ deviceId ‖ controllerId ‖ cbor)   raw r‖s, 64 bytes
  plaintext = sig ‖ cbor
  message   = ver(0x03) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_cmd, nonce, plaintext, aad) ‖ tag(16)
  aad       = 0x03 ‖ deviceId ‖ controllerId ‖ 0x01
  ```
  The `seq`/`ts` replay guard runs after the signature check, as before. Events don't change.
- **The device refuses every command that isn't signed with the bound key.** A pending pairing takes only `bind`. A pairing with no key that isn't pending (made before this decision, or past its deadline) takes nothing; the device's controller list marks it *Pair again*.
- **Pairing code version 2.** The layout doesn't change, but an older controller app refuses the code with a clear message instead of pairing and then being ignored.

## Consequences
- **A leaked `K` no longer presses.** A photographed QR is useless once bound. A copied blob or a memory dump gives `K` but not the signing key, which never leaves the secure hardware.
- **Root on a stolen controller phone** can sign only within 5 min of the last unlock, and only on that phone. After that the secure hardware refuses without the screen-lock credential.
- **Every controller is paired again** after the update. There are only a few, and a remote bind for old pairings would let anyone holding an old `K` bind first.
- **A prompt every 5 min of use:** a controller left open longer asks for the screen lock before the next press. A status request on reconnect after the window fails quietly, as one that fails today does.
- **A future Quick Settings tile** (`TODO.md`) presses only within the window, or after a prompt.
- **A controller whose screen lock is removed** loses its signing keys, as it already loses its vault (ADR 0028), and is paired again.
- **The remaining race** is someone scanning the QR before the legitimate controller while the dialog is open at the device. The fingerprint shown on both phones exposes it.
- **An iPhone controller** needs a P-256 key in the Secure Enclave, which CryptoKit offers.

## Alternatives
- **Passkeys or FIDO2 through Android's Credential Manager:** a passkey is tied to a web domain that must publish a Digital Asset Links file, which this project, with no server of its own, doesn't have. Passkeys synced through a password manager also aren't tied to one phone. A plain Keystore key gives the same guarantee without either.
- **Key attestation** (the device checks Google's certificate chain on the controller's key): it proves the key is in secure hardware and belongs to this app, but it needs Google's root pinned, ASN.1 parsing and revocation checks on the device. Possible later.
- **No authentication on the signing key:** no extra prompts, but root on a stolen, switched-on controller phone presses at will.
- **An authentication prompt for every press** (a `CryptoObject` per signature): the strongest option, but it rules out any press without a prompt.
- **Bind old pairings remotely:** whoever holds an old `K` could bind first.
- **Signature outside the encryption:** it would show the relay a signature from which the controller's public key can be recovered, and make the device run an ECDSA check on garbage.
- **Ed25519:** the Keystore has it only from API 33; minSdk is 26.
