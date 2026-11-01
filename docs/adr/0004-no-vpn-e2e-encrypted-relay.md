# 4. No VPN for controllers: an end-to-end-encrypted relay

- Status: Accepted (supersedes the earlier Tailscale design, which was never implemented)
- Date: 2026-10-15

## Context
The app must work from anywhere, and the link from controller to device must be securely encrypted.

Tailscale (WireGuard, end-to-end) was chosen first over Cloudflare Tunnel, because Cloudflare decrypts traffic at its edge. However:
- **Android and iOS allow only one VPN at a time.** A controller phone running a commercial VPN can't run Tailscale at the same time; split tunnelling doesn't help.
- Controller users may keep a general-purpose VPN on permanently.

## Decision
Controllers need **no VPN**.
- **Transport:** the device and the controllers both connect **outbound** to a public MQTT relay over TLS (ADR 0005).
- **Encryption:** every command and event is **end-to-end encrypted inside the app** (ADR 0006).
- **Trust:** the relay is treated as untrusted.

## Consequences
- **Works with any VPN:** the controller works from any network alongside any VPN. The device listens on no ports.
- **What the relay sees:** random topic IDs, message sizes, timing, IP addresses, and a plain online flag.
- **What the relay can't do:** read content, or forge or replay presses.
- **What the relay can do:** drop or delay messages (denial of service).
- **Cost:** we own a small crypto and protocol layer, and the controller is a native app rather than a web page.
- **Optional maintenance access:** the device phone may still run Tailscale purely for remote `adb`. It never needs another VPN, so there's no conflict.

## Alternatives
- **Tailscale on both phones:** simplest, but clashes with other VPNs on the controller phone.
- **Tailscale with a VPN provider's exit nodes instead of the user's own VPN:** only works if users give up their own VPN.
- **Cloudflare Tunnel:** TLS terminates at Cloudflare, so it isn't end-to-end.
