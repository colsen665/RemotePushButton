# Protocol (v1)

This describes the wire protocol between the device and its controllers. The reference implementation is `android/core`. Background is in ADRs 0004, 0005, 0006, 0010, 0016 and 0030.

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
| `v` | int | 1 |
| `deviceId` | bytes(16) | |
| `controllerId` | bytes(16) | |
| `key` | bytes(32) | master key `K` |
| `broker` | map | `{host, port, tls, username, password}`: the controller's broker credentials |
| `deviceName` | text | display name |

## Keys
```
salt   = deviceId ‖ controllerId
K_cmd  = HKDF-SHA256(ikm=K, salt, info="rpb1 cmd", L=32)
K_evt  = HKDF-SHA256(ikm=K, salt, info="rpb1 evt", L=32)
```

## Envelope
```
message = ver(0x01) ‖ nonce(24) ‖ XChaCha20-Poly1305(key, nonce, plaintext, aad) ‖ tag(16)
aad     = ver ‖ deviceId ‖ controllerId ‖ dir      dir: 0x01 = cmd, 0x02 = evt
```
- The nonce is random for every message.
- If decryption fails, the message is dropped silently.

## Job envelope (version 2, ADR 0030)
The events of a job whose command carried `epk` go under a key of their own, so a later leak of `K` doesn't open them:
```
epk_ctl  = the controller's ephemeral X25519 public key, from the command (fresh per job)
epk_dev  = the device's ephemeral X25519 public key (fresh per job)
K_job    = HKDF-SHA256(ikm=X25519(own private, peer public), salt=K_evt, info="rpb2 job" ‖ epk_ctl ‖ epk_dev, L=32)
message  = ver(0x02) ‖ epk_dev(32) ‖ nonce(24) ‖ XChaCha20-Poly1305(K_job, nonce, plaintext, aad) ‖ tag(16)
aad      = ver ‖ deviceId ‖ controllerId ‖ dir      dir: 0x02 = evt
```
- The device erases its private key right after deriving `K_job`; the controller erases its own once the job's first event has given it `epk_dev`. Both drop `K_job` when the job ends or times out.
- The job ID isn't in the header: the controller tries the keys of its open jobs and takes the job whose key opens the message, then checks that the event names that job.
- A shared secret of all zeros (a low-order `epk`) is refused.
- Commands, and `status` replies, stay version 1. A controller accepts version 1 for `status` only and drops any other event under the pairing key. The device still answers a command without `epk` in version 1, which no current controller sends.

## Messages
The plaintext is CBOR. Message types are a sealed hierarchy encoded by kotlinx-serialization as a two-element array: `[typeName, {fields}]`. Decoders ignore unknown fields.

### Commands (controller → device)
| Type | Fields |
|---|---|
| `press` | `seq`, `ts`, `job`, `delayMs` (1000 when absent, capped at 10 000; the app sends the delay set for the device, 3000 by default, ADR 0019), `epk?` |
| `status?` | `seq`, `ts` |
| `calib_photo` | `seq`, `ts`, `job`, `pressDelayMs?`, `epk?`: photo and read. With `pressDelayMs`, the device presses first and waits that long (capped at 10 000), as in `press`; absent, it doesn't press (ADR 0021) |
| `set_calib` | `seq`, `ts`, `job`, `calibration`, `pressDelayMs?`, `epk?`: save the calibration, then answer as `calib_photo` |

`epk` is the controller's ephemeral X25519 public key for the job, 32 bytes as a byte string. With it, the device answers the job's events in a job envelope; without it (older controllers), in a version 1 envelope. A command whose `epk` the device can't use is dropped.

**Replay rules on the device:**
- `seq` must be greater than the last `seq` accepted from that controller; the value is persisted.
- |device clock − `ts`| must be at most 120 s.
- A command failing either rule is dropped.

Controllers use `seq = max(lastSeq + 1, now in ms)`. `job` is 8 random bytes in hex.

### Events (device → controller)
Events of a job travel in a job envelope; `status` in a version 1 envelope, the only version 1 event a controller accepts (ADR 0030).

| Type | Fields | When |
|---|---|---|
| `ack` | `job` | immediately, if the device isn't busy |
| `busy` | `job` | another job is running |
| `calib` | `job`, `calibration` | in `calib_photo` and `set_calib` jobs, after `ack` and before `text`: the calibration as saved |
| `text` | `job`, `text`, `valid`, `confidence`, `digits[{d, conf}]`, `pressedAt` (0 if the job didn't press), `capturedAt` | after the photo is read; `?` marks an unknown digit |
| `photo` | `job`, `i`, `n`, `bytes` | JPEG split into chunks of ≤ 64 KB, 0-based `i` of `n` |
| `error` | `job?`, `code` (`press_failed`, `camera_failed`, `internal`, `bad_calibration`), `msg` | the job was aborted |
| `status` | `ts`, `battery`, `temp`, `charging`, `botBattery?`, `rssi?` | reply to `status?` |

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

**Controller timeouts:** 15 s for the `ack`, 60 s for the whole job.
