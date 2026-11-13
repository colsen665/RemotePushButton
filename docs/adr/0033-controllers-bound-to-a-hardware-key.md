# 33. Each pairing is bound to a signing key in the controller phone's Keystore

- Status: Accepted
- Date: 2026-11-12
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (pairing, command envelope), [0028](0028-keystore-key-bound-to-the-screen-lock.md) (keys at rest), [0030](0030-forward-secrecy-for-job-events.md) (job envelope version 3 signed by the device, `status` as a job, nothing under the pairing key)

## Context
Whoever holds a pairing's master key `K` can press as that controller until it is revoked on the device. `K` can leak in three ways: a photographed pairing QR (a known limitation in `docs/security.md`), a blob copied from a phone with its screen-lock credential or an exploit of the secure hardware (ADR 0028), or the memory of a controller with the app open. In each case the thief can press from any phone, anywhere.

The other direction has the same weakness. Events are authenticated by `K` alone: someone holding `K` who can read commands and publish events (the device's broker credential, or the relay) can derive a job key from a command's `epk` with an ephemeral key of their own, and answer in the device's place with a false code, photo, status, or a false `bound`. They can't press or read a real result, but the controller can't tell their answer from the device's.

A FIDO security key or a device-bound passkey solves the same problem: a private key that can't leave the phone's secure hardware, a public key on the other side, and a signature on every request. Here the controller phone can play that role, so a pairing works from that one install only.

## Decision
- **One signing key per pairing on the controller:** EC P-256 with SHA-256 in the Android Keystore, purpose sign only, not exportable. It is created in StrongBox where the phone has one and in the TEE otherwise (ADR 0029), with the alias `rpb-sig-<deviceId>-<controllerId>`.
  - **It needs the screen lock or a strong biometric within the last 5 min:** `setUserAuthenticationParameters(300, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)` on API 30 and later, `setUserAuthenticationValidityDurationSeconds(300)` before, and `setInvalidatedByBiometricEnrollment(false)`. A press after the window shows the screen-lock prompt first, then goes through.
  - **It needs an unlocked phone** on API 28 and later (`setUnlockedDeviceRequired(true)`), so a locked phone can't sign even within the window.
  - Removing a pairing deletes its key, and so does a wipe of the app. When the controller's store opens, it deletes any `rpb-sig-*` key with no stored pairing, except one being bound in that process.
- **Bind, the first command after the scan:** `bind {seq, ts, job, epk, pub}`, where `pub` is the X.509 SubjectPublicKeyInfo of the new key. The command is signed by the key it carries, which proves the controller holds it. The device answers `bound {job, fingerprint}` in the job envelope, signed by the device's key like every event (below), so someone holding `K` can't forge the reply: a false `bound` with the controller's own fingerprint is dropped.
  - **Fingerprint:** the first 8 bytes of SHA-256 of `pub`, written as four groups of four hex characters. Both phones show it so the user can compare them: the device in its controller list, the controller after the bind and with the pairing.
  - **A lost `bound`:** the controller sends the same `bind` (same `job`, `epk` and `pub`, a fresh `seq`) once more before giving up. The device answers a `bind` from an already-bound controller with `bound` again when it carries the bound key and is signed by it, and refuses one with any other key.
  - After a successful bind the controller asks for status, since the request it sent while the pairing was pending was dropped.
- **The first bind wins, and only while the QR is shown.** A new pairing is pending, with a deadline 10 min after the QR appears. The device accepts one `bind` while the QR dialog is open and the deadline hasn't passed. Closing the dialog, the dialog leaving the screen (screen off, the app in the background, the app PIN lock), or reaching the deadline before a bind removes the pairing; the dialog keeps the screen on while it shows. When the device app opens its secrets, it removes every pending pairing, since no dialog can be showing then. Once bound, the QR is worthless.
- **Signed command envelope, version 3** (sign, then encrypt, so the relay never sees the signature, and only a `K` holder reaches the signature check):
  ```
  sig       = ECDSA-P256-SHA256(sk_ctl, "rpb3 cmd" ‖ deviceId ‖ controllerId ‖ cbor)   raw r‖s, 64 bytes
  plaintext = sig ‖ cbor
  message   = ver(0x03) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_cmd, nonce, plaintext, aad) ‖ tag(16)
  aad       = 0x03 ‖ deviceId ‖ controllerId ‖ 0x01
  ```
  The `seq`/`ts` replay guard runs after the signature check, as before.
- **The device refuses every command that isn't signed with the bound key.** A pending pairing takes only `bind`. A pairing with no key that isn't pending (made before this decision, or past its deadline) takes nothing; the device's controller list marks it *Pair again*.
- **One signing key on the device,** so that only the device can answer: EC P-256 with SHA-256 in the device phone's Keystore, purpose sign only, not exportable, in StrongBox where the phone has one (ADR 0029), and without user authentication, as the device runs unattended. Its SubjectPublicKeyInfo is in every pairing code as `devicePub`. The device app replaces a key it can't use only while no controller is bound, or when the Keystore calls the key permanently invalidated; otherwise it keeps the key and answers nothing until it can use it again, since the Keystore reports a passing failure like a lost key and a new key would cut off every controller. It tries the key again after 10 s, 1 min, then every 5 min, and starts answering once it loads. The pairing QR shows only while the device answers, with the key it signs with. The controller refuses a code without it, or with anything but a P-256 key in the standard encoding with its point on the curve, and keeps it with the pairing.
- **The device signs each job's key exchange once,** and every event of the job carries that signature inside the job envelope, version 3 (sign, then encrypt, as for commands):
  ```
  sig_dev   = ECDSA-P256-SHA256(sk_dev, "rpb3 job" ‖ 0x03 ‖ deviceId ‖ controllerId ‖ epk_ctl ‖ epk_dev ‖ job)   raw r‖s, 64 bytes
  plaintext = sig_dev ‖ cbor
  message   = ver(0x03) ‖ epk_dev(32) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_job, nonce, plaintext, aad) ‖ tag(16)
  aad       = 0x03 ‖ deviceId ‖ controllerId ‖ 0x02
  ```
  `job` is the job ID from the command, in UTF-8, last as the only field whose length varies; `K_job` is derived as in ADR 0030. The device signs once the command has passed the replay guard, so a replay costs no signature, and before it stores a bind, so a bind it can't answer isn't kept.
  - **The controller checks it once per job,** on the first event that opens under the job key, whichever event that is: against `devicePub`, its own job ID and both ephemeral keys. One that fails is dropped and logged, and the job keeps its private key and waits for the device's own answer. Once one passes, the job key is the device's, and the job's later events must carry the same signature.
  - **In every event, not only the first:** the `ack` can be lost and events arrive out of order, so whichever arrives first must prove the job. 64 bytes per event is nothing against 64 KB photo chunks, and signing once keeps the device to one Keystore signature per job.
  - Version 2 job envelopes, which carry no signature, are refused.
- **`status?` is a job of its own:** it carries a fresh `job` and `epk` like every job command, and the device answers `status {…, job}` under that job's key, signed. The job ID and `epk` are the request's nonce: the signature covers both, and only that request's key opens the answer.
  - **Freshness:** the controller takes a `status` only as the answer to a request it has open, for the ack timeout (15 s) after sending it, and never an older request's answer over a newer one's. The device never sends a status unasked. Instead of a status pushed when the device comes online, the controller asks whenever the retained online flag reads `1`: on every connect while the device is online, and whenever the device comes back. The flag isn't authenticated, so it opens one request at a time, at most one per 5 s; flags that arrive meanwhile are answered by one more request afterwards, and a relay that repeats the flag can't make the phones sign without limit.
- **Nothing goes under the pairing key alone.** The device drops a command without `epk`, so every event belongs to a job. The controller drops any event that isn't in a version 3 job envelope: version 1 under `K_evt`, which carried `status` and could carry an `error` without a job, and version 2. The device sends no `error` outside a job; `job` stays optional in `error` only for the CBOR layout.
- **Pairing code version 2** adds `devicePub`. An older controller app refuses the code with a clear message instead of pairing and then being ignored.

## Consequences
- **A leaked `K` no longer presses.** A photographed QR is useless once bound. A copied blob or a memory dump gives `K` but not the signing key, which never leaves the secure hardware.
- **Nor does it answer.** Someone holding `K`, with the relay or the device's broker credential, can't put a false code, photo, status or `bound` in front of the controller: each needs the device's signature over a key exchange made for that job. They can still drop or delay events, which ends a job as a timeout, as the relay always could.
- **The device's key is as safe as the device phone.** Root on it, or an exploit of its secure hardware, signs as the device; whoever has that already presses the button. The key needs no unlock, so a device phone that restarts keeps answering.
- **The QR carries about 100 bytes more** (the 91-byte key and its field name), about 140 more characters of the code, and is a little denser to scan.
- **One ECDSA signature per job on the device,** made before the `ack`, and one check per job on the controller. A StrongBox signature can take a noticeable part of a second; to be measured on the phones. A status request is now a job: an X25519 exchange and a signature for a few bytes of status.
- **Root on a stolen controller phone** can sign only on that phone, while it is unlocked (API 28 and later) and within 5 min of the last unlock. Otherwise the secure hardware refuses without the screen-lock credential. Before API 28, a locked phone still signs within the 5 min.
- **Every controller is paired again** after the update. There are only a few, and a remote bind for old pairings would let anyone holding an old `K` bind first.
- **A prompt every 5 min of use:** a controller left open longer asks for the screen lock before the next press. A status request on the online flag after the window fails quietly, as one that fails today does.
- **A future Quick Settings tile** (`TODO.md`) presses only within the window, or after a prompt.
- **A controller whose screen lock is removed** loses its signing keys, as it already loses its vault (ADR 0028), and is paired again.
- **The remaining race** is someone scanning the QR before the legitimate controller while the dialog is open at the device. The fingerprint shown on both phones exposes it.
- **An iPhone controller** needs a P-256 key in the Secure Enclave and P-256 verification, which CryptoKit offers.

## Alternatives
- **Passkeys or FIDO2 through Android's Credential Manager:** a passkey is tied to a web domain that must publish a Digital Asset Links file, which this project, with no server of its own, doesn't have. Passkeys synced through a password manager also aren't tied to one phone. A plain Keystore key gives the same guarantee without either.
- **Key attestation** (the device checks Google's certificate chain on the controller's key): it proves the key is in secure hardware and belongs to this app, but it needs Google's root pinned, ASN.1 parsing and revocation checks on the device. Possible later.
- **No authentication on the signing key:** no extra prompts, but root on a stolen, switched-on controller phone presses at will.
- **An authentication prompt for every press** (a `CryptoObject` per signature): the strongest option, but it rules out any press without a prompt.
- **Bind old pairings remotely:** whoever holds an old `K` could bind first.
- **Signature outside the encryption:** it would show the relay a signature from which the controller's public key can be recovered, and make the device run an ECDSA check on garbage.
- **Ed25519:** the Keystore has it only from API 33; minSdk is 26.
- **The device's signature in the first event only,** or in a signed hello before the job's events: a lost or late first event would fail the job, which the signature in every event, made once, avoids for 64 bytes each.
- **A signature per event:** a Keystore signature per 64 KB photo chunk, with nothing gained: once the first event proves the job key is the device's, the AEAD authenticates the rest.
- **The device's signature in the clear,** in the header: the relay would see it and gain nothing it needs; inside the encryption only a `K` holder reaches the check, as for commands.
- **A static device key in the key exchange** (an X25519 or P-256 ECDH key in the QR mixed into `K_job`): authenticates the device without a signature, but the Keystore does P-256 key agreement only from API 31, and ADR 0030 rejected a static key in the job key for forward secrecy against theft of the device.
- **A status pushed by the device and kept fresh by its clock and a counter:** the controller would trust the device clock, store a counter per device, and still take a status the relay had held back for a while. Asking on each `online` costs one round trip, which the controller already makes on connect.
- **A nonce field in `status?` beside the job envelope's key under `K_evt`:** a second signed envelope for one event, where making `status?` a job reuses the one every other event has.
