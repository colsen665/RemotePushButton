# 5. HiveMQ Cloud free tier as the relay, speaking MQTT 3.1.1

- Status: Superseded by [0011](0011-emqx-cloud-serverless-relay.md) (HiveMQ retired Serverless; MQTT 3.1.1, the client library and 64 KB chunks carry over)
- Date: 2026-10-15

## Context
We need a relay both phones can reach outbound, with nothing to host.

## Decision
- **Service:** HiveMQ Cloud Serverless, free tier: 100 connections and 10 GB/month, no uptime guarantee, TLS on port 8883.
- **Client:** `com.hivemq:hivemq-mqtt-client`.
- **Protocol: MQTT 3.1.1 rather than 5.** Everything we need is in 3.1.1 (QoS 1, retained messages, last will), and it keeps any broker usable: Mosquitto, EMQX, Moquette. Tests use an embedded Moquette broker.
- **Credentials:** one MQTT credential for the device and one shared by controllers, both set in the HiveMQ console. They only keep strangers off the broker; real security comes from ADR 0006.
- **Message size:** photos are split into encrypted chunks of 64 KB, so broker size limits don't matter.

## Consequences
- **Data budget:** at about 400 KB per photo, the free tier allows roughly 25,000 presses a month.
- **Switching brokers** is a settings change: host, port, TLS, credentials.
- **Reconnect handling:** `MqttRelay` re-subscribes after every reconnect and dispatches all incoming messages through one global handler, so reconnects never create duplicate handlers.

## Alternatives
- **Self-hosted Mosquitto:** full control, but brings back infrastructure.
- **EMQX Cloud free tier:** equivalent; a fallback option.
