# Changelog

## 1.0.4

Maintainer review fixes (Nexus Store).

- Settings header now reads the real `versionName` from the package manager
  instead of a hardcoded string, so it never drifts from the actual version.
- Launcher and monochrome app icon now use Lume's own RSVP glyph everywhere:
  `ic_launcher_foreground` is an `<inset>` of the new monochrome
  `@drawable/nexus_glyph_lume` (was the generic scaffold glyph).

## 1.0.3

- Display no longer dims while reading. During playback the plugin was silent on
  the bus between buffer pages (several seconds with no surface activity), so the
  glasses dimmed mid-read while a paused screen — freshly updated on the pause
  tap — stayed lit. The reader now emits a keep-alive anchor resync every ~1.5 s
  while playing, keeping the hub rendering (and the display awake) throughout a
  read; the resync also corrects any small playback-clock drift.

## 1.0.2

Second round of on-device fixes.

- Reader now streams as a **sliding buffer**. On a CXR-only link the hub rejects
  any JSON surface larger than 3 KiB (it needs the SPP data plane), which is why
  documents past ~100 words never rendered. The reader now sends a small
  ~60-word window with per-window relative timestamps (well under 3 KiB), plays
  it on the glasses clock, and pages the next window in before the read head
  runs out — reading of arbitrary-length documents works without SPP.
- Documents open **paused**; a tap starts playback (was auto-playing on open).
- Removing a document no longer blanks the settings screen: the rebuild is now
  deferred off the click dispatch instead of tearing down the view mid-click.
- Reading progress is persisted on a throttle while playing, and the buffer
  re-anchors to the live position on each page so playback stays seamless.

## 1.0.1

Fixes from first on-device testing.

- Reader now opens. The timed-lines surface is built within a byte budget so a
  large document's window can never exceed the hub's 64 KiB surface ceiling
  (which was silently rejecting the whole surface, leaving the library card on
  screen). The window also pages down automatically for documents with very
  long extracted tokens.
- Surface protocol aligned with the shipped Transit/Lyrics plugins: a single
  surface is shown once per session and updated thereafter, and the
  library-card -> reader transition goes through updateTimedLines.
- BACK behaviour made coherent: a timed-lines surface cannot intercept BACK, so
  (like Lyrics) BACK from the reader now exits the plugin cleanly instead of
  leaving a stuck intermediate state; footers say so.
- Phone library list refreshes reliably: the store re-reads from disk on resume
  and on every rebuild, so documents added via the share target or the file
  picker always appear (and can be removed).
- Opening a document that yields no text now shows a visible message instead of
  silently doing nothing.

## 1.0.0

Initial Rokid Nexus port of Lume (headless phone-side plugin, id `lume`).

- RSVP reader rendered as a time-synced `NexusTimedLines` surface; the glasses
  hub plays the word stream on its own clock from a playback anchor.
- Adaptive pacing ported verbatim from the original `RsvpEngine` (long words,
  clause/sentence endings, paragraph breaks) accumulated into a document
  timeline, paged in windows to respect surface limits.
- Full R08 ring one-axis control: SELECT play/pause, NEXT/PREV speed while
  playing and sentence-step while paused, BACK to library / self-close.
- Phone library on the NexusUi kit: import PDF/TXT, paste text, default speed,
  English/Português, per-document progress, remove.
- Native share target for text and PDF/TXT files (single or multiple).
- Per-document reading progress persisted from the anchor clock.
- Unit tests for the RSVP timeline and the library/reader one-axis state
  machines (ring-navigability proof).
