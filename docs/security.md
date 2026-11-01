# Security model

Background is in ADRs 0004, 0006, 0015 and 0016.

## Assets
- The **press capability**: whoever can press, gets a fresh code.
- The **codes and photos** shown on the display.
- **Pairing keys** and **broker credentials**.

## Trust boundaries
| Party | Trusted? | Can see | Can do |
|---|---|---|---|
| Paired controller (holds `K`) | yes | everything for its pairing | press, take a calibration photo, change the calibration, ask for status |
| Relay (EMQX Cloud) and network | **no** | topic IDs, message sizes, timing, IP addresses, the online flag, broker usernames | drop or delay messages (denial of service) |
| Someone with the broker credentials but no `K` (a lost or revoked controller phone, until the credential is rotated) | no | the same as the relay, limited by the broker's authorisation rules | publish garbage, which is dropped; flood the broker |
| Someone within Bluetooth range of the Bot | no | nothing | press the button with any BLE app if no Bot password is set; with one, replay a press they recorded (ADRs 0003, 0025) |
| Physical access to the device phone | yes, by design, unless a PIN is set (ADR 0015) | everything | pair new controllers |
| Someone holding an unlocked controller phone | no, if a PIN is set | the PIN prompt only | guess the PIN: 3 tries, then a wipe or growing waits |
| Someone with `adb` access to a phone | no, on a release build | the preferences and the encrypted blob, which stays encrypted | nothing further; on a debug build, `run-as` reads the diagnostics and decrypts the blob (ADRs 0024, 0026) |

## Properties
- **Confidentiality and integrity:** XChaCha20-Poly1305 with keys derived per direction and per pair. The additional authenticated data binds each message to its version, device, controller and direction.
- **Replay protection:** a strictly increasing `seq` per controller (persisted), plus a ±120 s timestamp window. Events carry neither: job IDs are random, travel encrypted and are known to the controller only while the job runs, so a replayed event matches nothing.
  - A captured command can't be replayed, even within the window.
  - Testing: `RelayIntegrationTest` re-publishes captured commands and checks they are ignored.
- **Authorisation:** pairing needs the QR code shown on the device screen. Revoking deletes the controller's key on the device, after which its commands fail authentication and are ignored.
- **At rest:** keys and broker credentials are encrypted with an Android Keystore AES-GCM key. The controller keeps no codes or photos on disk: its history is in memory only, each entry for 5 min, and cleared when the PIN lock engages (ADR 0026). Nothing is included in cloud backup or device-to-device transfer, so a new phone starts unpaired (ADR 0020).
- **PIN lock (optional, ADR 0015):** asked on start and after more than 60 s away. The PIN is stored as a salted PBKDF2 hash in the encrypted blob, and failures are counted before checking. With wipe on, 3 wrong PINs erase all app data and the Keystore key; with wipe off, waits grow from 30 s to 1 h, measured on the time since boot, so setting the clock forward doesn't skip them (ADR 0024).
- **Broker access (ADR 0024):** the device's MQTT client ID is random and secret, so nobody can take its session over; authorisation rules on the broker keep each credential to its own topics; the device never logs a code to logcat.
- **Builds:** the phones run signed release builds, so `adb shell run-as` can't reach the app's files or act as its UID (ADR 0024).
- **Screen capture (ADR 0020):** `FLAG_SECURE` blanks the Recents thumbnail and blocks screenshots and screen recording in both modes, so the last code or a pairing QR doesn't linger on screen.
- **Clipboard:** a copied code is marked sensitive, so Android 13 and later hide it in the clipboard preview.
- **No inbound ports** on either phone.

## Known limitations
- **No forward secrecy:** anyone who records traffic and later steals `K` can decrypt it. Follow-up: ephemeral X25519 session keys (see `TODO.md`).
- **Metadata:** the relay learns when presses happen and roughly how big the photos are.
- **Shared controller credential:** all controllers use one broker credential. It only gates broker access, not commands. Revoking a controller doesn't revoke it: after a lost phone, rotate it on the broker and pair the other controllers again.
- **Pairing QR leaks:** anyone who sees the QR code can pair until that controller is revoked. Close the QR dialog once paired.
- **Calibration from any controller (ADR 0016):** any paired controller can change the calibration. A bad one makes reads fail or, at worst, read wrongly; the photo is always sent, and the device refuses calibrations that would break the reader. The calibration itself carries no code.
- **Diagnostics (ADR 0023):** with **Save diagnostics** on, the device keeps its newest full-size photos, which show codes, in app-private storage for `adb`. Off by default; turning it off deletes them. They are never sent.
- **Display anchor (ADR 0018):** to follow the display, the device keeps a reduced picture of the area around the digit row, with the digits blanked. It is stored in the encrypted settings and never sent.
- **Display limits:** a physically missing segment on the display can produce a confidently wrong digit (ADR 0008). The photo is always sent so the user can check.
- **PIN strength:** the PIN hash alone wouldn't resist offline guessing. It relies on the Keystore encryption of the blob and the attempt limit.
- **Old phone:** the device phone gets no OS updates. Keep it dedicated to this app, with no accounts or other apps, and USB debugging off once set up.
- **Bot in Bluetooth range:** without a password, the SwitchBot Bot takes presses from anyone in range. Its password (ADR 0025) goes over the air as a CRC32, the same every time, so a recorded press can be replayed. Set it, and mount the Bot where strangers don't get within a few metres.
