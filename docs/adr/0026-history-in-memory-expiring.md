# 26. Controller history in memory only, kept 5 minutes and cleared on lock

- Status: Accepted
- Date: 2026-10-19
- Amends: [0010](0010-text-first-photo-backup-copy.md) (history), [0015](0015-app-pin-lock-with-optional-wipe.md) (what locking does)

## Context
The controller kept the last 50 results, codes and photos, as plain files in app storage, with no time limit. A code is used within a minute or two of the press. After that, the stored codes and photos only add exposure:
- after unlocking, anyone holding the phone sees the history;
- on a debug build, `run-as` reads the files (ADR 0024);
- a rooted phone reads them too.

ADRs 0020 and 0024 named encrypting the history as a later fix.

## Decision
- **Memory only.** The history lives in the app process (`History` in `core`) and is never written to disk. Builds from before this ADR left a `files/history` directory; the app deletes it on start.
- **5 minutes per entry**, timed on the time since boot, so changing the wall clock doesn't keep an entry alive. While the controller screen is visible, expiry is checked every second, and once when the screen returns.
- **The code and photo at the top of the screen** expire the same way, including "Photo only" results, which have no history entry.
- **Cleared on lock.** When the PIN prompt appears (on start, or after more than 60 s away, ADR 0015), the history and the current result are cleared. Open dialogs showing an expired or cleared code close. Without a PIN the app never locks, so only the 5 minutes apply.

## Consequences
- **No codes or photos at rest on the controller.** Encrypting the history is no longer needed, and a wipe (ADR 0015) has no history left to erase.
- **History is lost when the process dies,** for example if Android kills the app while the user pastes the code into another app. Pressing again gives a fresh code.
- **An entry can outlive 5 minutes in memory** while the app is in the background. It is removed as soon as the screen returns, before anyone can see it. Memory of a running app can't be read without root.
- **The clipboard isn't cleared.** A copied code stays in the clipboard, which ADR 0010 marks as sensitive.

## Alternatives
- **Keep the files and prune expired ones on start:** files from a closed app outlive the 5 minutes.
- **Files plus an alarm that deletes them:** more code, and the timing isn't exact under Doze.
- **Encrypt the files with the Keystore key:** still at rest, and a debug build can use the key (ADR 0024).
