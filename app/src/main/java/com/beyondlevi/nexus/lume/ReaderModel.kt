package com.beyondlevi.nexus.lume

/**
 * Pure RSVP timeline model — the reading brain of the Nexus port, with no
 * Android or SDK dependencies so it is fully unit-testable on the JVM.
 *
 * The original standalone Lume streamed word windows to the glasses and let the
 * glasses tick each word with [RsvpEngine.delayMsFor]. In Nexus the glasses hub
 * plays a time-synced surface on its own clock, so instead of ticking we
 * accumulate the same per-word delays into an absolute timeline (the start
 * time, in ms, of every word) and hand a window of it to the hub. Play / pause
 * / seek / speed then become anchor math over this timeline.
 *
 * A [TimedWord] is a `(timeMs, text)` pair: the display text with the paragraph
 * marker stripped, at its absolute start time in the document.
 */
class ReaderModel(private val words: List<String>, initialWpm: Int) {

    data class TimedWord(val timeMs: Long, val text: String)

    var wpm: Int = initialWpm.coerceIn(RsvpEngine.MIN_WPM, RsvpEngine.MAX_WPM)
        private set

    val size: Int get() = words.size

    /** Absolute start time (ms) of each word at the current [wpm]; strictly increasing. */
    private var starts: LongArray = LongArray(0)

    /** Sorted indices at which a new sentence begins (0 plus every index after a sentence-ender). */
    private val sentenceStarts: IntArray = computeSentenceStarts()

    init {
        rebuildTimeline()
    }

    /** Recomputes the timeline for a new speed. Returns nothing; the caller maps
     *  the current word to its new [posMsForWord] so the reader stays on the same word. */
    fun setWpm(newWpm: Int) {
        val clamped = newWpm.coerceIn(RsvpEngine.MIN_WPM, RsvpEngine.MAX_WPM)
        if (clamped == wpm) return
        wpm = clamped
        rebuildTimeline()
    }

    /** Absolute start time of a word, clamped to valid indices. */
    fun posMsForWord(index: Int): Long {
        if (starts.isEmpty()) return 0L
        return starts[index.coerceIn(0, starts.size - 1)]
    }

    /** The word visible at an absolute clock position: the last word whose start time is <= posMs. */
    fun wordIndexAt(posMs: Long): Int {
        if (starts.isEmpty()) return 0
        if (posMs <= 0L) return 0
        var lo = 0
        var hi = starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * The word window to send as a surface, centered on [current] with a little
     * back-context, capped at [windowSize] entries. Returns `first until last`.
     */
    fun windowRange(current: Int, windowSize: Int = DEFAULT_WINDOW): IntRange {
        if (size == 0) return IntRange.EMPTY
        val w = windowSize.coerceIn(1, MAX_WINDOW)
        val start = (current - BACK_CONTEXT).coerceIn(0, (size - 1).coerceAtLeast(0))
        val end = (start + w).coerceAtMost(size)
        return start until end
    }

    /** The timed words for a range, display text stripped of the paragraph marker. */
    fun timedWords(range: IntRange): List<TimedWord> =
        range.map { i -> TimedWord(starts[i], TextMarks.strip(words[i])) }

    /**
     * Sentence stepping (used while paused, mirroring the original's swipe):
     * `dir < 0` rewinds to the start of the current sentence, or to the previous
     * sentence when already at a boundary; `dir > 0` skips to the next sentence.
     */
    fun sentenceStep(current: Int, dir: Int): Int {
        if (size == 0) return 0
        val clamped = current.coerceIn(0, size - 1)
        return if (dir < 0) {
            val here = currentSentenceStart(clamped)
            if (clamped > here) here else previousSentenceStart(here)
        } else {
            nextSentenceStart(clamped)
        }
    }

    private fun rebuildTimeline() {
        val out = LongArray(words.size)
        var acc = 0L
        for (i in words.indices) {
            out[i] = acc
            acc += RsvpEngine.delayMsFor(words[i], wpm)
        }
        starts = out
    }

    private fun computeSentenceStarts(): IntArray {
        if (words.isEmpty()) return IntArray(0)
        val list = ArrayList<Int>()
        list.add(0)
        for (i in words.indices) {
            if (i + 1 < words.size && endsSentence(words[i])) list.add(i + 1)
        }
        return list.distinct().sorted().toIntArray()
    }

    private fun endsSentence(rawWord: String): Boolean {
        if (TextMarks.endsParagraph(rawWord)) return true
        val last = TextMarks.strip(rawWord).lastOrNull { !it.isWhitespace() }
        return last == '.' || last == '!' || last == '?' || last == '…'
    }

    private fun currentSentenceStart(index: Int): Int {
        var ans = 0
        for (s in sentenceStarts) {
            if (s <= index) ans = s else break
        }
        return ans
    }

    private fun previousSentenceStart(sentenceStart: Int): Int {
        var prev = 0
        for (s in sentenceStarts) {
            if (s < sentenceStart) prev = s else break
        }
        return prev
    }

    private fun nextSentenceStart(index: Int): Int {
        for (s in sentenceStarts) {
            if (s > index) return s
        }
        return (size - 1).coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_WINDOW = 800
        const val MAX_WINDOW = 2_000
        const val BACK_CONTEXT = 60
    }
}
