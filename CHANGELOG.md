# Changelog

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
