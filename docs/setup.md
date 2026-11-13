# Setup

## 1. Broker (EMQX Cloud Serverless, free)
1. Sign up at EMQX Cloud and create a **Serverless** deployment in a region near the device. Don't pick **Dedicated Flex**: that is the plan with the 14-day trial. Serverless has a monthly free quota and no trial period.
   - Leave the **spending limit at 0**, so the deployment stays free. It stops for the rest of the month if the quota runs out; it never bills you.
   - Note the address from the overview page (`xxxx.….emqxsl.com`; port 8883, TLS).
2. Under **Access Control → Authentication**, add two username/password credentials:
   - `rpb-device`, with a strong password, used only on the device phone;
   - `rpb-controllers`, with a strong password, which goes into the pairing QR codes.
3. Under **Access Control → Authorization**, set the default to **deny** and add rules per username (ADR 0024):
   - `rpb-device`: allow **publish** `rpb/+/online` and `rpb/+/c/+/evt`; allow **subscribe** `rpb/+/c/+/cmd`.
   - `rpb-controllers`: allow **subscribe** `rpb/+/online` and `rpb/+/c/+/evt`; allow **publish** `rpb/+/c/+/cmd`.
4. The free quota is 1 GB of traffic a month, roughly 40–50 presses a day (ADR 0011). The overview page shows usage; check it during the first weeks.

## 2. Build and install
The phones run the **release** build: it isn't debuggable, so `adb shell run-as` can't read the app's files or use its Keystore key (ADR 0024). It needs a signing key, made once and kept out of the repo. With JDK 21 (Temurin) and `adb` on your `PATH`:
```sh
keytool -genkeypair -keystore ~/.local/share/rpb/release.jks -alias rpb -keyalg RSA -keysize 4096 -validity 10000
cat >> android/local.properties <<EOT
rpb.keystore=$HOME/.local/share/rpb/release.jks
rpb.keystorePassword=<password>
rpb.keyAlias=rpb
rpb.keyPassword=<password>
EOT
cd android && ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk   # on each phone
```
Keep the key file and its password: a release build signed with another key can't update the installed app. `local.properties` is gitignored.

The debug build (`:app:assembleDebug`, `app-debug.apk`) is for pulling diagnostics off the device phone only (Troubleshooting). Switching between the two needs `adb uninstall net.rpb.app` first, which erases the app's data, so pair again afterwards.

**Updating from a build before ADR 0028:** set a screen lock on the phone first (§4 step 1, §5 step 1), then install over the old build. On its first start the app asks for the screen lock and moves the pairings to the new key; nothing needs pairing again.

**Updating from a build before ADR 0033:** update the device phone and every controller phone, then pair every controller again (§5): each pairing is now bound to a signing key on its controller phone and to the device's own signing key, carried in the new QR codes, and old pairings take no commands. The device lists them as *Pair again*; **Remove** them there, and remove the old pairing on the controller phone. An older app refuses the new QR codes, and the new app refuses QR codes from an older device app.

The top line of every screen shows the installed build: `Build <n> · <commit> · <time>` is the number of commits, the commit (with `+` if built with uncommitted changes) and the build time.

## 3. SwitchBot Bot
1. In the SwitchBot app (on any phone), set the Bot to **Press mode**.
   - Note its **MAC address**: Bot settings → Device info.
   - Set a **password**: Bot settings → Password. Enter the same one under **SwitchBot password** in the device's Settings (§4). It stops casual presses from other phones in range, but can be recorded and replayed (ADR 0025).
2. Mount the Bot so its arm presses the membrane button squarely. Test it from the SwitchBot app.
3. Close the SwitchBot app on the device phone, or don't install it there. The Bot accepts only one connection at a time.

## 4. Device phone (the old Android)
1. Factory reset. Set up Wi-Fi and **automatic date & time**; skip accounts where possible. Set a **screen lock** (a PIN) and keep it somewhere safe: the app's keys open only with it (ADR 0028). Removing it later erases the app's pairings.
2. Install the APK and open it. It asks for the screen lock; then choose **Device**. Grant the camera, Bluetooth (nearby devices) and notification permissions. On Android 11 and below it asks for location too, and location must stay on: the app scans briefly for the Bot when the service starts and after a failed connect (ADR 0031). It never uses the location. Without that permission the device still runs, but may not reach the Bot after a reboot; the device screen then shows **Allow scanning**.
3. When Android asks, make **RemotePushButton** the default **Home** app ("Always"). After a reboot the phone stops at its lock screen and the device stays offline until someone unlocks it; then the app comes back on screen and goes online. If the app crashes while the phone is locked, its notification says *Unlock the phone to bring the device back online*.
4. Tap **Disable battery optimisation**.
   - **Asus phones:** also turn off the battery-drain detection, which kills the app regardless of the exemption: `adb shell settings put global asus_detect_bd_on 0` (check with `settings get`; a system update may reset it).
   - While USB debugging is on, `adb logcat -G 16M` keeps hours of the device log instead of minutes, until the next reboot. The log never contains codes.
5. In **Settings**:
   - device name;
   - Bot MAC address, and the Bot password if you set one;
   - **Torch while taking photos**: leave on when the rig keeps the display dark;
   - broker host, exactly as the EMQX console shows it (a mistyped host still resolves, see Troubleshooting), and port, TLS on;
   - device and controller credentials; **Show** in a password field reveals what you typed;
   - **Save diagnostics**: leave off. Turn it on only to investigate failed reads (see Troubleshooting).
   
   Then tap **Save**. The relay status should turn to *running*.
6. Mount the phone rigidly with the camera facing the display, within focus distance.
7. Tap **Test press**, then **Test photo**.
8. Calibrate the reader. Best from a controller phone once it is paired (§5 step 4), so nobody touches the mounted phone and shifts it. On the device itself, tap **Calibrate**. Either way:
   1. Leave **Press the button first** ticked, so each calibration photo shows a fresh code (ADR 0021). Untick it if the display already shows one.
   2. Enter the code shown and tap **Find digits**. The app finds the digit row, which way up it is and whether it's an LCD or LED display. The read turns green.
   3. Tap **Save**. The device remembers the display's surroundings (the LCD frame and the logo) and from then on follows the display if the mount moves by up to about two digit heights, so small movements don't need a new calibration (ADR 0018). Saved on the device itself, it learns them from the calibration photo right away, since tapping the phone can move it (ADR 0022); saved from a controller, at its first correct read.

   If **Find digits** doesn't find the code: check the code and the focus, and **Retake photo**. As a last resort, open **Adjust by hand…**: drag the yellow corners roughly around the digit row (pinch to zoom; zoomed in, drag off the corners to pan) and tap **Refine**; *Turn 90°*, the digit count, polarity and sliders are there too.
9. Optional: under **PIN lock**, set an app PIN, and choose whether 3 wrong PINs erase all app data. It guards the pairing QR and the settings from someone handed the unlocked phone; a wipe takes the device offline until it is set up and paired again. The same dialog shows **Key storage**: *StrongBox*, *hardware* or *software* (ADR 0029).
   Then turn **USB debugging** off (Developer options; **Revoke USB debugging authorisations** too). Authorising a computer needs the screen lock, but the phone gets no security updates. Turn it on again only to pull diagnostics or logs (ADR 0024).
10. **Charging:** the phone stays plugged in, so limit the charge to protect the lithium battery.
   - **Asus phones:** Settings → Battery → Battery care → **Charging limit**, set to **80%**. Charging pauses at 80% and resumes after a small drop. Turn on **Slow charging** too, which keeps the battery cooler. Those without bypass charging have no better option.
   - **Other phones:** turn on the charge limit if there is one (often "Protect battery" or "80% limit").
   - **No charge limit**, or the limit doesn't hold: put the charger on a smart plug that cycles charging between roughly 40% and 80%.
   - Turn off automatic system updates (Settings → System → System updates). Users report the Asus limit failing after firmware updates.
   - The controller shows a warning when the device reports more than 85% while plugged in (the limit isn't holding) or a battery temperature of 40 °C or more. The check assumes an 80% limit.
   - Keep the phone out of direct sun and enclosed warm spots, and check the battery for swelling now and then.

## 5. Controller phone(s)
1. On the device phone: **Controllers → Add controller**, enter a name, and keep the QR code on screen. The screen stays on while the QR dialog shows.
2. On the controller phone: make sure it has a **screen lock** (ADR 0028). Install the APK and open it; it asks for the screen lock. Choose **Controller** and tap **Scan pairing QR** within 10 min of the QR appearing. The controller binds its new signing key to the pairing, and the device's QR dialog turns from *Waiting for the controller…* to *Bound* (ADR 0033).
   - **Compare the fingerprints:** both phones show four groups of four characters. They must match. If the device shows *Bound* and the controller doesn't, or the fingerprints differ, another phone scanned the QR first (Troubleshooting). Then close the QR dialog on the device.
   - Closing the dialog before the controller has bound, letting it leave the screen (turning the screen off, the app PIN lock, another app), or waiting more than 10 min, removes the pairing: add the controller again.
   - Later, the controller shows its fingerprint for the selected device at the bottom of its screen, as *This phone's key fingerprint, as the device lists it under this controller*; the device lists each controller's fingerprint under **Controllers**.
   - The app forgets its keys when it leaves the screen (60 s after, with an app PIN) and asks for the screen lock again if the phone was unlocked more than 30 s before.
   - A press more than 5 min after the phone's screen lock was last passed shows the screen-lock prompt first, then goes through: the signing key works only within 5 min of it (ADR 0033).
3. Optional: **PIN lock** sets an app PIN for this phone, asked after the screen lock. The screen lock already protects the keys; the app PIN adds a second step for someone handed the unlocked phone.
4. Calibrate the device from here, if it isn't yet (§4 step 8): tap **Calibrate**. With **Press the button first** ticked, the device presses, waits the delay set for it and sends a photo. Enter the code in the photo, tap **Find digits**, then **Save**. The device saves the calibration, presses again and answers with its own read and a fresh photo.
5. Tap **PRESS**. The code appears with a **Copy** button, and the photo follows below. The device photographs the display 3 s after the press; if the photo still shows the old code, tap **Photo 3 s after the press** and set a longer wait (up to 10 s, kept for each device).
6. **Erasing the device app's data** (a wipe after wrong app PINs, **Erase and start again**, or an uninstall) also replaces the device's signing key, so every controller must be paired again: remove the device on each controller and add it again with a new QR (ADR 0033).
7. A lost phone? Use **Revoke** on the device phone. Then, on EMQX, change the `rpb-controllers` password, enter it in the device's Settings, **Save**, and pair the remaining controllers again with new QR codes: the old password stays in the lost phone and would still let it reach the broker (ADR 0024).

## Troubleshooting
| Symptom | Check |
|---|---|
| Relay stays stopped | Broker host and credentials; the device phone's internet connection; the EMQX deployment isn't stopped for exceeding its monthly quota. The log at the bottom of the device screen shows the reason. `ConnectTimeoutException` usually means a mistyped host: any name under `emqxsl.com` resolves, but only the deployment's own host (`<id>.ala.<region>.emqxsl.com`, with dots) answers on port 8883 |
| "Device offline" on the controller | The device service is running (its notification is showing); the phone has network access; battery optimisation is disabled; on Asus, `asus_detect_bd_on` is 0. `adb shell dumpsys activity exit-info net.rpb.app` shows why the app last stopped |
| Press failed | Bot MAC address, Bot password (the same in both apps), Bot within about 5 m, Bluetooth on, SwitchBot app closed, Bot battery |
| Presses time out connecting to the Bot after a reboot of the device phone ("Bot attempt failed (…Timed out…)" in the device log) | The app scans for the Bot before its first connect, so after a reboot the first battery read should succeed. If the log says "connecting to the Bot without a scan", grant the scan permission (**Allow scanning** on the device screen; on Android 11 and below, location, with location on). If it says "Bot not seen in a 5 s scan", check the Bot MAC address, the Bot's battery and range, and that the SwitchBot app isn't connected to it. As a stopgap, opening Android's Bluetooth **Pair new device** screen for a few seconds, without pairing anything, also makes the next press connect |
| "Charging past 80 %" on the controller | The device phone's charge limit is on and set to 80%; a system update may have reset it. Otherwise use a smart plug (§4 step 10) |
| "Battery is hot" on the controller | Direct sun or enclosed mounting (see the idle test in `TODO.md`) |
| Wrong or amber digits | Re-calibrate from a controller with **Find digits**; if one digit washes out, tilt the light or camera against glare; check focus distance |
| Reads failed after the rig was bumped | The device follows the display for moves up to about two digit heights or 5° ("display followed" in the device log). It learns the display's surroundings at Save on the device, or at the first correct read after calibrating from a controller ("display anchored"). Further than that ("display not found around its calibrated position"), re-calibrate |
| Blank display in the photo, all digits `?` | A press while the display still shows a code turns the display off. Wait until the code has gone before pressing again |
| Reads fail and the log doesn't say why | Install the debug build on the device phone (§2; uninstall first, and pair again). Turn on **Save diagnostics** in the device's Settings and press a few times. Then `adb shell run-as net.rpb.app ls files/diagnostics` lists each photo (`<ms>-photo-t<turn>.jpg`, turn it `<turn>`° clockwise to be upright) and each job's calibration (`<ms>-job-<id>.json`); copy one with `adb exec-out run-as net.rpb.app cat files/diagnostics/<name> > <name>`. The photos show codes: turn it off afterwards, which deletes them (ADR 0023), and put the release build back (ADR 0024) |
| Ack timeout | The phones' clocks: commands are rejected if they differ by more than 120 s |
| "Device offline" after a power cut or a reboot; the notification says *Unlock the phone…* | The device phone waits at its lock screen: unlock it (ADR 0028) |
| The device log says *device signing key missing, unreadable or wrong, and kept* | The phone's Keystore couldn't use the device's signing key, and the device answers nothing meanwhile: the notification says it is waiting for the key, and **Add controller** is off. The device tries again after 10 s, 1 min, then every 5 min, and comes online once the key loads. Restart the app, or the phone. If it stays so, the key is lost: **Revoke** every controller, then pair them again (§5), which makes a new key (ADR 0033) |
| *Pair again* next to a controller on the device | It was paired before ADR 0033, and its presses are dropped. **Remove** it, remove the pairing on the controller phone, and pair it again (§5) |
| *Binding failed: show the QR again on the device* on the controller | The QR dialog was closed or left the screen (screen off, app PIN lock, another app), or more than 10 min passed: add the controller again on the device and scan the new QR. If the device's dialog shows *Bound*, see the next row |
| The device shows *Bound* but the controller failed to bind, or the fingerprints differ | Another phone scanned the QR first and is bound in its place. **Revoke** that controller on the device and add it again, keeping the QR out of sight of cameras |
| *The device bound another phone's key to this pairing* on the controller | As in the row above: unless another phone of yours scanned the same QR, someone holding the code bound first. **Revoke** that controller on the device and add it again |
| *Answers not signed by the device were dropped* on the controller | Answers came for this device that only someone holding the pairing's key could make, without the device's signature (ADR 0033). They were dropped, so the codes shown are the device's own. **Revoke** this controller on the device, remove the device on the controller, and pair again (§5); if a lost phone held the pairing, rotate the controller credential too (§5 step 7). The warning shows until the pairing is removed or the app's process ends. It also shows on every answer if the device had to replace its signing key; the device log then says *new device signing key: every controller paired before must pair again*, and the remedy is the same |
| The controller refuses the pairing QR | One of the two apps is older than ADR 0033: update the one the message names |
| "Set a screen lock first" | The phone has no screen lock; set one, then **Try again** |
| "The app's data can't be opened" | The screen lock was removed, which destroys the app's key. **Erase and start again**, then set up and pair again |
