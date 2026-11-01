# 25. Optional SwitchBot Bot password

- Status: Accepted
- Date: 2026-10-19
- Amends: [0003](0003-switchbot-bot-over-direct-ble.md) (command bytes)

## Context
The SwitchBot Bot takes presses from anyone in Bluetooth range, using the SwitchBot app or any BLE app. The SwitchBot app can set a password on the Bot. After that the Bot accepts only commands that carry the password, and the plain commands of ADR 0003 fail, so the setup told the user to leave the password off.

The Bot has no stronger protection. The password goes over the air as its CRC32, unencrypted and the same every time, so anyone who records one press can replay it.

## Decision
- **Optional password in the device settings,** blank by default. It is stored with the other secrets in the Keystore-encrypted blob and never logged.
- **Command bytes,** built in `core` (`BotCommands`). With a password, the command byte gets `0x10` added and the 4-byte big-endian CRC32 of the password follows it:
  - press: `57 11 <crc> 00`;
  - basic info: `57 12 <crc>`.
  
  Without a password, the bytes are those of ADR 0003.
- **The setup recommends setting the password,** in the SwitchBot app and then in RPB.

## Consequences
- **Casual presses stop.** Someone in range with a stock app can no longer press the button. To press it, an attacker has to be in range during one of the user's presses, record it, and replay it.
- **The password must match on both sides.** If they differ, every press fails with the Bot's status code in the log and on the controller.
- **Placement still matters.** `docs/security.md` keeps the advice to mount the Bot out of reach of strangers' radios.

## Alternatives
- **Wired actuator over USB** (a microcontroller with a servo or solenoid, driven over USB-OTG): no radio, so it can't be pressed without physical access. Rejected for now because it means building hardware; it remains the fallback in ADR 0003.
- **Own BLE actuator with real authentication** (an ESP32 using LE Secure Connections bonding, or an HMAC challenge-response): it can't be replayed, but it needs hardware, firmware and a battery to manage.
- **No password, placement only:** the setup until now; it leaves any phone in range able to press.
