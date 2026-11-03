# 30. A job's events go under a per-job key from ephemeral X25519 keys

- Status: Accepted
- Date: 2026-11-03
- Amends: [0006](0006-e2e-crypto-and-qr-pairing.md) (event encryption, the "no forward secrecy" limitation)

## Context
Every message of a pair is encrypted under keys derived from the pairing key `K` (ADR 0006). Anyone who records the relay traffic and later gets `K`, from a stolen phone's blob (ADR 0028) or a pairing QR seen over a shoulder, can decrypt everything recorded so far: every code and photo the device sent. Codes are short-lived, but photos show the display and the history of reads is exactly what the project keeps off disk (ADRs 0026, 0027).

Commands carry no secrets: `press`, `calib_photo`, `set_calib` and `status?` hold a job ID, delays and a calibration. Events carry the codes and photos.

## Decision
- **Each command that starts a job** (`press`, `calib_photo`, `set_calib`) carries a fresh X25519 public key `epk` from the controller: a new optional CBOR byte string (`ignoreUnknownKeys`).
- **The device answers the job's events under a job key.** It makes its own ephemeral key pair, and derives
  `K_job = HKDF-SHA256(ikm = X25519(own private, peer public), salt = K_evt, info = "rpb2 job" ‖ epk_controller ‖ epk_device)`.
  The salt `K_evt` ties the job key to the pairing, so the relay can't put its own key in the header and forge events. The shared secret is what `K` alone can't recompute once both private keys are gone.
- **Envelope version 2:** `ver(0x02) ‖ epk_device(32) ‖ nonce(24) ‖ ciphertext ‖ tag(16)`, XChaCha20-Poly1305, AAD `ver ‖ deviceId ‖ controllerId ‖ dir` as in version 1. The device's public key travels in the clear.
- **No job ID in the clear.** The controller tries each open job's key on a version 2 message and takes the job whose key opens it, then checks that the event names that job. With one or two jobs open, that is one or two X25519 operations on the first event and none after.
- **Keys are dropped as soon as they can be.** The device erases its private key right after deriving the job key, and drops the job key when the job ends. The controller erases its private key once the first event has given it the device's key, and drops the job key when the job ends or times out. The job key's bytes are zeroed as soon as Tink holds its own copy; that copy can only be dropped, and stays in the heap until the garbage collector reuses it.
- **Everything else stays as it is.** Commands stay under `K_cmd`, and the `status` reply, which belongs to no job, stays under `K_evt`, version 1.
- **The controller accepts version 1 envelopes for `status` only.** Any other event under the pairing key is dropped and logged as "dropped version 1 job event", so a job's events reach the controller only under the job's key. The device still answers a command without `epk` in version 1, as an older controller sends it.
- **No pairing version bump:** the pairing code and `K` are unchanged, so nothing is paired again.

## Consequences
- **Recorded codes and photos stay sealed after `K` leaks.** Someone with recorded traffic and `K` reads the commands (when presses happened, the delays, the calibrations sent) and the status replies, but not the codes or photos of a finished job.
- **A thief with `K` can still press** until the controller is revoked, and gets the new codes like any controller. Forward secrecy protects the past, not the future. They can't forge a result into a live job.
- **A device older than this decision doesn't work with a current controller:** its acks are dropped, and every press ends in "device did not acknowledge"; the controller log says why. There is one device, and it runs this version.
- **During a job**, the keys are in memory on both phones; root on a phone mid-job reads that job, and may find a dropped job key in the heap for a while after.
- **32 bytes more** per event in the header and 32 in each job command; nothing that matters against the 64 KB photo chunks or the quota.
- **A downgrade by the relay isn't possible:** the device chooses the version, and the relay can't make a version 1 event without `K_evt`. Someone who has `K` can make version 1 events, but the controller takes only `status` from them.
- **An iPhone controller** needs X25519 and HKDF-SHA256, which libsodium and CryptoKit both have.

## Alternatives
- **Ratchet the pairing key** (a new `K` after each job, as in Signal's symmetric ratchet): one lost message desynchronises the two sides, and several controllers per device would each need their own state on the device; QoS 1 over a relay that may drop messages makes that fragile.
- **Ephemeral keys for commands too:** commands hold nothing secret, and the device can't answer a command it can't read, so it would need a round trip before each press.
- **The job ID in the header** to route events: it would give the relay a per-job tag linking the command to its events; trying the keys of the few open jobs costs nothing.
- **A long-lived device key pair in the QR (static-ephemeral):** gives no forward secrecy against theft of the device phone, whose blob would hold the static private key.
- **Keep accepting version 1 job events,** so that older devices keep working: the device chooses the version, so someone holding `K` could still send a version 1 ack, text or error into a live job.
- **A per-pairing flag learned from the first version 2 event,** refusing version 1 afterwards: keeps older devices working until updated, at the cost of a stored flag and a window on the first job. With no older device, it protects nothing that the plain refusal doesn't.
- **A pairing version bump,** so a version 2 QR means "refuse version 1": every controller would be paired again for no gain.
