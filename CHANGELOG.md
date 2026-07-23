# Changelog

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
