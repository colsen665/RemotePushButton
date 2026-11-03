# 31. A short scan for the Bot's address before the first connect and after a failure

- Status: Accepted
- Date: 2026-11-03
- Amends: [0003](0003-switchbot-bot-over-direct-ble.md) (connect by MAC address with no scan)

## Context
ADR 0003 connects to the SwitchBot Bot by its MAC address with no scan, so the app needed neither `BLUETOOTH_SCAN` nor location. On the rig, after a reboot of the device phone, every connect timed out (10 s, `TimeoutCancellationException`): the battery read at start and the first presses. A visit to Android's Bluetooth *Pair new device* screen, which scans, fixed it with nothing changed on the Bot. Until the phone has seen the Bot advertise since boot, the Bluetooth stack doesn't know the device behind the address, likely including its address type, and a direct `connectGatt` never completes. More retries don't help: `withRetry` already tries twice, and the stack only learns the Bot from an advertisement.

## Decision
- **Scan for the Bot's address** with `BluetoothLeScanner`, a `ScanFilter` on the address and low-latency mode, for at most 5 s, and stop at the first result. Connect to the `BluetoothDevice` of that result, which carries what the stack learnt from the advertisement.
- **When:** on the first connect of each service start, and on the retry after a failed attempt. Otherwise `BotClient` reuses the device it connected to last. After a reboot the first connect is the battery read at start, so the presses that follow connect directly. A Bluetooth restart or a Bot reset that makes the stack forget the Bot costs one failed attempt, and the retry scans.
- **If the scan can't run or finds nothing,** `BotClient` logs why and connects by address as before. The scan never makes a press fail.
- **Permissions:** `BLUETOOTH_SCAN` with `neverForLocation` on Android 12 and later; `ACCESS_FINE_LOCATION`, declared up to Android 11 only, and location turned on, on Android 11 and below. The device screen asks for them with the camera, Bluetooth and notification permissions, and on its own when only they are missing, as after an update from a build without them. The service starts without them; the screen then shows that scanning isn't allowed, with a button to ask again.

## Consequences
- **No extra time on the normal press,** which connects directly. The first connect after a service start takes up to the Bot's advertising interval longer, and up to 5 s if the Bot isn't seen.
- **Few scans:** at most one per Bot operation, and none in steady use, so the app stays far from Android's limit of 5 scan starts in 30 s, beyond which scans silently find nothing.
- **More permissions:** *Nearby devices* now covers scanning too. `neverForLocation` means the app can't derive location from the scan, and Android may drop some beacon advertisements from its results, which the Bot doesn't use.
- **Android 11 and below** need the location permission and location on to scan. Without them the device works as before this decision. On Android 10 and 11, a scan while the app isn't in the foreground (the screen off) may also find nothing without `ACCESS_BACKGROUND_LOCATION`, which the app doesn't ask for; that falls back to the direct connect too.

## Alternatives
- **Scan before every connect:** robust against any loss of the stack's cache, but adds the scan to every press and runs into the scan-start limit under repeated presses. The retry after a failure covers the rarer losses.
- **More attempts with a longer gap:** doesn't fix the cause; the connect only succeeds once something else has scanned.
- **Pair (bond) the Bot:** the stack would remember it across reboots, but SwitchBot's protocol works without bonding, and whether the Bot accepts it is untested.
- **The *Pair new device* screen after each reboot:** the workaround until now; it needs someone at the device phone.
