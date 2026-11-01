# 10. Text first, photo as backup; in-app Copy button

- Status: Accepted; amended by [0026](0026-history-in-memory-expiring.md) (history in memory only, 5 min)
- Date: 2026-10-15

## Context
What the user actually wants is the code, ready to paste. The photo is there to check the result if the read is uncertain.

## Decision
- **Order:** after a press the device sends `text{job, text, valid, confidence, digits, pressedAt, capturedAt}` first. It's tiny and arrives in about 3 s. The JPEG follows in encrypted chunks (about 1600 px on the long side, quality 80).
- **Display:** the controller shows the code in large digits with a **Copy** button, and the photo below it.
  - Copying marks the clip as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), so Android 13 and later hide it in the clipboard preview.
  - An invalid or low-confidence read is shown in amber with "check photo", and copying it needs a confirmation.
- **Shortcuts:** no Quick Settings tile, widget or icon shortcut for now. Only the in-app button, which is what the user chose.
- **Timing:** `pressedAt` and `capturedAt` let the 1 s delay be verified from the controller.

## Consequences
- **Graceful degradation:** a failed or slow photo transfer doesn't block getting the code.
- **Easy to extend:** a Quick Settings tile could reuse `ControllerClient.press()` later.
