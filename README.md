# RemotePushButton

Press a physical membrane button from anywhere, and get back the code its display shows.

1. You tap **PRESS** in the app on your phone.
2. A retired Android phone next to the target has a SwitchBot Bot press the button.
3. A few seconds later (3 s by default, set per device in the app) it photographs the 7-segment display and reads the digits.
4. It sends the code back first, ready to **Copy**, with the photo following as a backup.

Traffic goes through a free MQTT relay and is end-to-end encrypted, so it works alongside any VPN.

- **Status:** running on real phones with the rig; phone hardening is still to do. A 12 h idle test on battery passed; there is no soak test, so the device isn't tested under many presses in a row. See [TODO.md](TODO.md).
- **Setup:** [docs/setup.md](docs/setup.md)
- **How it works:** [AGENTS.md](AGENTS.md), [docs/protocol.md](docs/protocol.md), [docs/security.md](docs/security.md)
- **Why it's built this way:** [docs/adr/](docs/adr/)
- **Licence:** [MIT](LICENSE)
