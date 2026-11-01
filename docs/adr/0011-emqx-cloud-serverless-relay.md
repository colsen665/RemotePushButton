# 11. EMQX Cloud Serverless as the relay (supersedes 0005)

- Status: Accepted; amended by [0024](0024-relay-hardening-and-release-builds.md) (authorisation rules, random client IDs)
- Date: 2026-10-16
- Supersedes: [0005](0005-hivemq-cloud-mqtt-311-relay.md)

## Context
HiveMQ retired Cloud Serverless. No new clusters since 2026-09-30, existing ones stop on 2026-12-31. The remaining HiveMQ Cloud plan, Starter, is billed hourly (from $0.34/h, about $250 a month), far more than this project warrants. ADR 0005 already named EMQX Cloud as the fallback.

## Decision
- **Service:** EMQX Cloud Serverless, free with the monthly spend limit left at 0:
  - 1 M session minutes and 1 GB traffic (inbound plus outbound) per month;
  - TLS on port 8883, username/password authentication, no uptime guarantee.
- **Unchanged from 0005:**
  - MQTT 3.1.1;
  - the `com.hivemq:hivemq-mqtt-client` library, which is a generic Apache-2.0 client, not tied to HiveMQ's service;
  - one device credential and one shared controller credential;
  - photos sent as 64 KB encrypted chunks.
- The security model is unchanged: the relay was already untrusted (ADRs 0004, 0006).

## Consequences
- **Session minutes are not the constraint.** The device stays connected around the clock, about 43,200 minutes a month. Controllers connect only while their screen is visible.
- **Traffic is the constraint.**
  - A press with one controller listening costs about 2 × the photo size, since the photo goes in to the broker and out to the controller.
  - At 300–400 KB per photo that is roughly 1,200–1,600 presses a month, about 40–50 a day.
  - Each additional controller that has the app open during a press adds another photo's worth.
- **Hitting the quota stops the deployment until the next month.** That is a denial of service for every controller. Watch the usage on the deployment overview, which updates hourly. Shrinking the photo is tracked in `TODO.md`.
- **To check on first connect:**
  - the broker's TLS certificate validates against Android's system trust store;
  - retained messages and the last will (the `online` flag) behave as with Moquette in the tests.
- **Switching brokers** is still only a settings change.

## Alternatives
- **HiveMQ Cloud Starter:** too expensive for a single device.
- **HiveMQ self-hosted with a free Lab license, or Mosquitto:** free, but needs a host reachable from the internet. That brings back the infrastructure ADR 0004 avoids. It remains the fallback if the EMQX free tier goes away.
- **Public test brokers (broker.emqx.io, test.mosquitto.org):** no access control. Anyone could flood the topics, and they are for testing only.
