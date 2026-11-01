# 2. A retired Android phone is the remote device

- Status: Accepted
- Date: 2026-10-15

## Context
The remote unit has to:
- press a membrane button on another device,
- wait 1 s,
- take a photo,
- read the digits on the display,
- and reach the internet.

The first design used an ESP32-S3 camera board with a servo, plus a home server.

## Decision
Repurpose a retired **Android** phone, running our own app, as the device.

## Consequences
- **Better hardware:** a much better camera (autofocus, auto exposure, flash), Wi-Fi with optional cellular, and a battery that works as a backup supply.
- **Enough compute:** the digits can be read on the phone itself.
- **Needs an actuator:** a phone can't press a button by itself; see ADR 0003.
- **Android restrictions:** background camera access is limited (ADR 0009), and Android aggressively kills background apps. Countermeasures: a persistent background service, exemption from battery optimisation, and wake locks.
- **Battery swelling:** an old lithium battery on charge around the clock can swell. Use the phone's charge limit, or a smart plug that cycles charging between about 40% and 80%, and inspect the phone periodically.
- **No OS security updates:** mitigated because the phone listens on no ports (connections go outbound only), runs only our app, and the content is end-to-end encrypted (ADR 0004).
- **iPhone rejected:** an iPhone would need the app kept on screen (Guided Access), because iOS doesn't allow background camera use.

## Alternatives
- **ESP32-S3 camera board with servo or solenoid, plus a home server:** cheaper per unit, but a weaker camera and more custom firmware and infrastructure.
