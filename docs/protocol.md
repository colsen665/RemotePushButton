# Protocol (v1)

This describes the wire protocol between the device and its controllers. The reference implementation is `android/core`. Background is in ADRs 0004, 0005, 0006, 0010, 0016, 0030 and 0033.

## Transport
- **MQTT 3.1.1** over TLS (port 8883). All publishes use **QoS 1**, and only the presence flag is retained.
- **Client IDs:** `rpb-dev-` or `rpb-ctl-` plus 16 random hex chars: fixed per device and kept secret, fresh for every controller connection. They are never derived from the IDs in the topics, since a broker drops a session when another client connects with its ID (ADR 0024).
- **Device last will:** `rpb/{deviceId}/online` = `0`, retained.

| Topic | Direction | Payload |
|---|---|---|
| `rpb/{deviceId}/c/{controllerId}/cmd` | controller → device | encrypted command |
| `rpb/{deviceId}/c/{controllerId}/evt` | device → controller | encrypted event |
| `rpb/{deviceId}/online` | device → all | plain `1` / `0`, retained |

`deviceId` and `controllerId` are 16 random bytes, written as lowercase hex in topics.

## Pairing code (QR)
`rpb1:` + base64url (no padding) of a CBOR map:

| Field | Type | Meaning |
|---|---|---|
| `v` | int | 2 |
| `deviceId` | bytes(16) | |
| `controllerId` | bytes(16) | |
| `key` | bytes(32) | master key `K` |
| `devicePub` | bytes(91) | SubjectPublicKeyInfo of the device's signing key (ADR 0033) |
| `broker` | map | `{host, port, tls, username, password}`: the controller's broker credentials |
| `deviceName` | text | display name |

Version 2 adds `devicePub`, and says the device expects a `bind` (ADR 0033). The app refuses any other version: an app older than ADR 0033 refuses version 2 codes, and a current controller refuses version 1 codes and asks for the device app to be updated. A controller also refuses a code without `devicePub`, or with anything but a P-256 key in that encoding with its point on the curve (*Public keys*, below), and keeps it with the pairing.

## Keys
```
salt   = deviceId ‖ controllerId
K_cmd  = HKDF-SHA256(ikm=K, salt, info="rpb1 cmd", L=32)
K_evt  = HKDF-SHA256(ikm=K, salt, info="rpb1 evt", L=32)
```

## Envelopes
- The nonce is random for every message.
- A message that doesn't open is dropped.
- Nothing goes under the pairing key alone: commands go under `K_cmd`, signed by the controller, and events under a key per job, signed by the device (ADR 0033). `K_evt` is only the salt of the job keys.

## Signed command envelope (version 3, ADR 0033)
Every command is signed by the controller's signing key for the pairing, then encrypted under `K_cmd`, so the relay never sees the signature:
```
signed    = "rpb3 cmd" ‖ deviceId ‖ controllerId ‖ cbor        "rpb3 cmd": 8 ASCII bytes; IDs: 16 bytes each
sig       = ECDSA-P256-SHA256(sk_ctl, signed)                   raw r ‖ s, 32 bytes each, big-endian
plaintext = sig(64) ‖ cbor
message   = ver(0x03) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_cmd, nonce, plaintext, aad) ‖ tag(16)
aad       = ver ‖ deviceId ‖ controllerId ‖ dir                 dir: 0x01 = cmd
```
- The device decrypts first and checks the signature only on a command that opens. Then it checks `epk`, and last the replay rules (below), so a command it drops for its signature or its `epk` uses up no `seq`.
- `sig` is checked against the controller's bound public key, or, for a `bind` from a pending controller, the key the `bind` carries. A signature that isn't 64 bytes, has `r` or `s` of 0 or not below the curve order, or doesn't verify, drops the command.
- **Public keys** are X.509 SubjectPublicKeyInfo, DER, for P-256 with the named curve and an uncompressed point (91 bytes); any other encoding, or a point not on the curve, is refused. So a key has one encoding and one fingerprint.
- **Fingerprint:** the first 8 bytes of SHA-256 of the SubjectPublicKeyInfo, as lowercase hex in four groups of four, separated by spaces: `1a2b 3c4d 5e6f 7081`.
- Version 1 commands are refused: the device opens version 3 only.

## Job envelope (version 3, ADRs 0030, 0033)
Every event belongs to a job and goes under that job's key, so a later leak of `K` doesn't open it, and carries the device's signature over the job's key exchange, so only the device can answer:
```
epk_ctl   = the controller's ephemeral X25519 public key, from the command (fresh per job)
epk_dev   = the device's ephemeral X25519 public key (fresh per job)
K_job     = HKDF-SHA256(ikm=X25519(own private, peer public), salt=K_evt, info="rpb2 job" ‖ epk_ctl ‖ epk_dev, L=32)
signed    = "rpb3 job" ‖ 0x03 ‖ deviceId ‖ controllerId ‖ epk_ctl ‖ epk_dev ‖ job     job: the job ID, UTF-8
sig_dev   = ECDSA-P256-SHA256(sk_dev, signed)                   raw r ‖ s, 64 bytes
plaintext = sig_dev(64) ‖ cbor
message   = ver(0x03) ‖ epk_dev(32) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_job, nonce, plaintext, aad) ‖ tag(16)
aad       = ver ‖ deviceId ‖ controllerId ‖ dir                 dir: 0x02 = evt
```
- `sk_dev` is the device's signing key; its public key is `devicePub` in the pairing code. The job ID is last in `signed`, as the only field whose length varies.
- **The device signs once per job,** after the command has passed the replay rules and before it stores a `bind`, and puts the same `sig_dev` in every event of the job.
- The device erases its private key right after deriving `K_job`; the controller erases its own once an event of the job has passed the signature check. Both drop `K_job` when the job ends or times out.
- The job ID isn't in the header: the controller tries the keys of its open jobs and takes the job whose key opens the message.
- **The controller checks `sig_dev` once per job,** on the first event that opens under the job's key, whichever event that is: against `devicePub`, its own job ID and both ephemeral keys. One that fails is dropped and logged, and the job keeps its private key and waits for the device's own answer. Once one passes, the job's later events must carry the same `sig_dev`.
- The event must name the job whose key opened it; one naming no job or another job is dropped.
- A shared secret of all zeros (a low-order `epk`) is refused.
- The controller drops any event that isn't a version 3 job envelope: version 2 (ADR 0030) carries no signature, and version 1 events under `K_evt` are no longer sent.

## Messages
The plaintext is CBOR. Message types are a sealed hierarchy encoded by kotlinx-serialization as a two-element array: `[typeName, {fields}]`. Decoders ignore unknown fields.

### Commands (controller → device)
| Type | Fields |
|---|---|
| `press` | `seq`, `ts`, `job`, `delayMs` (1000 when absent, capped at 10 000; the app sends the delay set for the device, 3000 by default, ADR 0019), `epk` |
| `status?` | `seq`, `ts`, `job`, `epk`: a job of its own, answered by `status` only (ADR 0033) |
| `calib_photo` | `seq`, `ts`, `job`, `pressDelayMs?`, `epk`: photo and read. With `pressDelayMs`, the device presses first and waits that long (capped at 10 000), as in `press`; absent, it doesn't press (ADR 0021) |
| `set_calib` | `seq`, `ts`, `job`, `calibration`, `pressDelayMs?`, `epk`: save the calibration, then answer as `calib_photo` |
| `bind` | `seq`, `ts`, `job`, `epk`, `pub` (bytes): the first command after the scan. `pub` is the SubjectPublicKeyInfo of the controller's signing key for this pairing, and the command is signed by that key (ADR 0033) |

`epk` is the controller's ephemeral X25519 public key for the job, 32 bytes as a byte string; the device answers the job's events in a job envelope under it. It is optional in the CBOR of every command but `bind`, from before ADR 0030, but the device drops a command without it, or with one it can't use (ADR 0033).

**Replay rules on the device:**
- `seq` must be greater than the last `seq` accepted from that controller; the value is persisted.
- |device clock − `ts`| must be at most 120 s.
- A command failing either rule is dropped.

**Binding rules on the device (ADR 0033):**
- **Pending:** a new pairing waits for its `bind` until 10 min after the QR appears, by the device clock. It takes only a `bind`, signed by the key in `pub`; any other command is dropped. Closing the QR dialog, the dialog leaving the screen (the screen turned off, the PIN lock, another app), or reaching the deadline, before a bind removes the pairing.
- **The first bind wins:** the device stores `pub` and answers `bound`. The check and the store are one step, so of two binds at once only one wins.
- **Bound:** the pairing takes only commands signed by its key. A `bind` signed by that key and carrying it gets `bound` again, so a controller whose answer was lost can retry; a `bind` with another key is dropped.
- **Past the deadline,** a pending pairing takes nothing, and the device app removes it. The device app also removes every pending pairing when its secrets are opened, since no QR dialog can be showing then.
- **Old pairings,** made before ADR 0033, have no key and take nothing; the device lists them as *Pair again*.
- A refused command gets no answer; a controller sees a refused `bind` as a timeout.
- **The controller resends a `bind` once:** with no `bound` within 15 s, it sends the same `bind`, with the same `job`, `epk` and `pub` and a new `seq` and `ts`, and waits 15 s more. A lost `bound` then doesn't fail the pairing, as the bound key's own `bind` gets `bound` again.

Controllers use `seq = max(lastSeq + 1, now in ms)`, and number and send one command at a time, so the commands of one controller reach the relay in `seq` order. The device checks commands against these rules in the order they arrive. `job` is 8 random bytes in hex.

### Events (device → controller)
Every event travels in the job envelope of the command it answers (ADR 0033).

| Type | Fields | When |
|---|---|---|
| `ack` | `job` | immediately, if the device isn't busy |
| `busy` | `job` | another job is running |
| `bound` | `job`, `fingerprint` (text) | answer to `bind`: the key is bound. The controller checks the fingerprint against its own key and shows it, as the device does |
| `calib` | `job`, `calibration` | in `calib_photo` and `set_calib` jobs, after `ack` and before `text`: the calibration as saved |
| `text` | `job`, `text`, `valid`, `confidence`, `digits[{d, conf}]`, `pressedAt` (0 if the job didn't press), `capturedAt` | after the photo is read; `?` marks an unknown digit |
| `photo` | `job`, `i`, `n`, `bytes` | JPEG split into chunks of ≤ 64 KB, 0-based `i` of `n` |
| `error` | `job?`, `code` (`press_failed`, `camera_failed`, `internal`, `bad_calibration`), `msg` | the job was aborted. The device always sets `job`; it is optional only in the CBOR layout |
| `status` | `ts`, `battery`, `temp`, `charging`, `botBattery?`, `rssi?`, `job` | answer to `status?`, naming its `job`; no `ack` before it |

### Calibration
`calibration` is a map `{config, rotation}` (`Calibration` in `core/ocr`):
- `rotation`: extra clockwise turn of every photo, 0, 90, 180 or 270.
- `config`: the reader settings (`SevenSegConfig`). Missing fields take their defaults.

| Field | Type | Meaning |
|---|---|---|
| `corners` | 4 × `{x, y}` | digit row TL, TR, BR, BL, as fractions (0–1) of the turned photo |
| `digits` | int | 1–12 |
| `slant` | float | lean of the top relative to the middle, in digit heights |
| `polarity` | text | `DARK_ON_LIGHT` (LCD) or `LIGHT_ON_DARK` (LED) |
| `onThreshold`, `minConfidence` | float | segment-on fraction; per-digit confidence needed for a valid read |
| `digitGap`, `segmentThickness` | float | fractions of the digit cell and of the digit width |
| `ledMultiplex` | bool | combine lit segments across frames |
| `alignSearch` | float | how far each read may re-align, in digit heights |

The device rejects a `set_calib` it can't use with `error` `bad_calibration` and keeps the old calibration: rotation not a quarter turn, corners outside 0–1 or a side shorter than 0.005, or values outside the ranges in `Calibration.problem`. A busy device answers `busy` and saves nothing.

## Press sequence
1. The controller sends `press`.
2. The device replies `ack`.
3. The Bot presses; `pressedAt` is recorded.
4. The device waits `delayMs`.
5. It takes a photo and reads it; `capturedAt` is recorded. If the read isn't valid, it takes up to two more frames about 150 ms apart.
6. The device sends `text`.
7. It sends `photo` chunks 0…n−1.

**Controller timeouts:** 15 s for the `ack`, 60 s for the whole job, 15 s for `bound` (twice, see the binding rules) and 15 s for `status`.

## Status (ADR 0033)
- **The controller asks whenever the retained `online` flag reads `1`:** on every connect while the device is online, and each time the device comes back. It asks once more after a successful bind, since a request sent while the pairing was pending was dropped.
- **One request at a time for the flag, at most one per 5 s:** the flag isn't authenticated and the relay can repeat it, and each request costs a signature on both phones. Flags that arrive while a request is open, or within 5 s of the last, are answered by one more request afterwards.
- **The device never sends a status unasked.** It answers `status?` with `status` naming the request's `job`, under that job's key, signed like every event.
- **Freshness:** the controller takes a `status` only as the answer to a request it has open, for 15 s after sending it, and never an older request's answer over a newer one's. The request's `job` and `epk` are its nonce: `sig_dev` covers both, and only that request's job key opens the answer.
- A request fails quietly, also when the controller's signing key needs the screen lock; the next one asks again.
