# 27. No real photos of the display or codes in the repo

- Status: Accepted
- Date: 2026-11-01
- Amends: [0008](0008-template-based-seven-segment-reader.md) (test fixtures)

## Context
The reader's tests ran on real photos of the display, with the codes they show in filenames, assertions and docs. Those codes and pictures of the device must not be published. Blurring or cropping can't help, since the tests need the digits.

## Decision
- **Drawn scenes.** `Scenes` in the `core` tests draws displays with invented codes: a generic body, LCD window and marks, thin blurred segments, tilt, uneven light, glare and noise. The calibration of each scene is known exactly.
- **Nothing real is committed:** no photo of the display, and no code it has shown, in any file, filename, test, doc or commit message. Image files are gitignored as a backstop.
- **Earlier ADRs keep their findings** on real photos, without the codes.

## Consequences
- The tests pin the reader's behaviour, not its accuracy on the real display. A change to the reader needs checking on the rig as well; "Save diagnostics" (ADR 0023) gives photos that stay off the repo.
- Scenes are cleaner than photos and don't reproduce every real failure, such as a start tilted too steeply for Refine.
- The locator misses some scenes after small changes to them (`TODO.md`), so scene positions and codes were chosen where it succeeds.
