# 32. Device settings apply without a restart; only a broker change restarts the service

- Status: Accepted
- Date: 2026-11-03
- Related: [0009](0009-device-app-as-home-launcher.md) (the service), [0011](0011-emqx-cloud-serverless-relay.md) (online flag, last will), [0006](0006-e2e-crypto-and-qr-pairing.md) (replay guard)

## Context
**Save** in the device settings restarted the device service. On the way down the service publishes the retained `online` = `0`, and the relay disconnects; the new service connects again a second or two later. MQTT sessions are clean, so a command published while no device is subscribed is dropped by the broker. A press sent in that gap got no `ack`, and the controller gave up after 15 s.

Every setting but one was already read when it was used, not when the service started:
- the Bot MAC and password, for each Bluetooth command;
- the torch and the photo rotation, each time the camera opens;
- "Save diagnostics", for each photo;
- the device name and the controller credentials, only when a pairing QR is made.

Only the device's broker settings (and its IDs, which Save never changes) are fixed at start, because the MQTT client is built from them. So the restart was needed only for a broker change. The calibration and the photo delay never restarted anything.

## Decision
- **Save restarts the service only when the device's broker settings change** (`DeviceSettings.needsRestart`). Other changes take effect from the next job, with the service, the relay connection and the `online` flag untouched.
- **A changed Bot MAC or password clears the Bot battery shown** and reads it again shortly after, as a restart used to.
- **A broker change still restarts**, and a press sent during that restart still fails on the controller after the `ack` timeout. That is rare and done by someone at the device phone; the controller shows the device offline meanwhile, and the user presses again once it is back.

## Consequences
- Fixing a Bot MAC, setting a Bot password or rotating the controller credential (ADR 0024) no longer takes the device offline.
- The protocol and `core` don't change. The decision is the adapter's: it is the one that builds the relay at start.
- A setting added later that is read only at start must be added to `needsRestart`.

## Alternatives
- **Persistent MQTT session (clean session off, QoS 1):** the broker would queue a command during the restart and deliver it on reconnect. Rejected: it also queues during any outage, and the replay guard accepts a command up to 120 s old. A press queued for longer than the controller's 15 s `ack` wait would run with nobody waiting for it, and a second press the user sent after the timeout would run too. Telling a queued command from a live one would need a shorter freshness window than the 120 s of clock skew the guard allows.
- **Controller retries once after an `ack` timeout when the device comes back online:** a retry would be a new command and pass the replay guard, so the device would press twice if it had received the first command and only its `ack` was lost. The controller can't tell those cases apart from the `online` flag: the flag and the events travel on different topics, with no ordering between them. Making it safe would need the device to remember job IDs across restarts, a protocol change, to save one tap in a rare case.
