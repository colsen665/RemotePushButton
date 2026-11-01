# 3. SwitchBot Bot, controlled directly over BLE, presses the button

- Status: Accepted
- Date: 2026-10-15

## Context
The press must be mechanical, without opening the target device. The phone needs some actuator it can control.

## Decision
Use an off-the-shelf **SwitchBot Bot** in Press mode.
- The device app talks to it **directly over Bluetooth Low Energy**, using SwitchBot's published protocol.
- It connects to the Bot by its known MAC address (no scanning).
- It writes the press command `0x57 0x01 0x00` to characteristic `cba20002-224d-11e6-9fb8-0002a5d5c51b`.
- It waits for the result notification on `cba20003-…`, where status `0x01` means success.

## Consequences
- **No electronics to build.** The Bot runs about a year on a coin cell.
- **Mounting:** it sticks on with adhesive, so it needs a flat surface next to the button, or a small printed bracket.
- **Native code required:** BLE needs Android's native APIs. Termux has no BLE access on an unrooted phone, which is why we went straight to a Kotlin app (ADR 0007).
- **Security:** the press command never leaves the phone's Bluetooth range, and no SwitchBot cloud or hub is involved.

## Alternatives
- **SwitchBot Hub plus SwitchBot's cloud API:** would have allowed a Termux prototype, but puts the press command outside our encryption, adds a cloud dependency and about 1–2 s of delay.
- **DIY servo driven by a USB or BLE microcontroller:** deterministic and tunable, but it means building hardware. This is the fallback if the Bot can't press the membrane reliably.
