package com.beyondlevi.nexus.lume

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusTimedLine
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest

/**
 * The Lume HUD runtime: a two-view (LIBRARY -> READER) one-axis state machine
 * that renders declarative Nexus surfaces and drives RSVP playback through the
 * time-synced surface's playback anchor.
 *
 * ## Buffered streaming (the important part)
 *
 * The hub only carries JSON surfaces larger than 3 KiB over the SPP data plane;
 * when SPP is not connected it rejects them (`NO_DATA_PLANE`). CXR — always
 * available — caps a control message at 3 KiB. So a whole document (or even a
 * few hundred words) cannot be sent as one timed-lines surface on a CXR-only
 * link.
 *
 * Lume therefore streams the reader as a **sliding buffer**: it sends a small
 * window (~60 words, comfortably under 3 KiB, with per-window relative
 * timestamps) that the glasses hub plays on its own clock, and pages the next
 * window in via `updateTimedLines` before the read head runs out — the Nexus
 * equivalent of the original app's word-window buffering, but bounded by the
 * CXR control-message size rather than the radio.
 *
 * Ring mapping (R08 Access Bridge), mirroring the original touchpad:
 *  - LIBRARY: NEXT/PREV move selection, SELECT opens the focused document,
 *    BACK exits the plugin.
 *  - READER:  SELECT toggles play/pause; while playing NEXT/PREV change speed;
 *    while paused NEXT/PREV step sentences; BACK exits the plugin. Documents
 *    open paused; a tap starts playback.
 */
class LumeRuntime(private val host: Host, private val store: DocumentStore, private val settings: SettingsStore) {

    interface Host {
        fun showCard(card: NexusCard, show: Boolean)
        fun showTimedLines(lines: NexusTimedLines, show: Boolean)
        fun updateTimedLinesAnchor(contentKey: String, anchor: NexusPlaybackAnchor)
        fun hide()
    }

    private enum class View { LIBRARY, READER }

    private var active = false
    private var shown = false
    private var view = View.LIBRARY
    private val library = LibrarySelection()
    private var documents: List<DocumentInfo> = emptyList()

    // Reader session state. Positions are tracked in ABSOLUTE document ms; the
    // surface payload uses per-window RELATIVE times to keep it small.
    private var model: ReaderModel? = null
    private var docId: String = ""
    private var docTitle: String = ""
    private var wpm: Int = RsvpEngine.DEFAULT_WPM
    private var anchorPosMs: Long = 0L
    private var playing: Boolean = false
    private var sentAtElapsed: Long = 0L
    private var windowRange: IntRange = IntRange.EMPTY
    private var contentKey: String = ""
    private var lastPersistElapsed: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!active || view != View.READER || !playing) return
            onProgressTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun open() {
        active = true
        shown = false
        view = View.LIBRARY
        refreshLibrary()
        renderLibrary()
    }

    fun close() {
        if (!active) return
        persistProgress()
        stopTicker()
        active = false
        model = null
        host.hide()
    }

    fun registrationApproved() {
        if (!active) return
        if (view == View.LIBRARY) renderLibrary() else renderReader()
    }

    fun input(event: NexusInputEvent) {
        if (!active) return
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> onNext()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> onPrev()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> onSelect()
            KeyEvent.KEYCODE_BACK -> close()
            else -> Unit
        }
    }

    /* ---------------- library view ---------------- */

    private fun refreshLibrary() {
        documents = store.documents()
        library.setCount(documents.size)
    }

    private fun onNext() {
        when (view) {
            View.LIBRARY -> { library.move(1); renderLibrary() }
            View.READER -> if (playing) changeSpeed(RsvpEngine.WPM_STEP) else stepSentence(1)
        }
    }

    private fun onPrev() {
        when (view) {
            View.LIBRARY -> { library.move(-1); renderLibrary() }
            View.READER -> if (playing) changeSpeed(-RsvpEngine.WPM_STEP) else stepSentence(-1)
        }
    }

    private fun onSelect() {
        when (view) {
            View.LIBRARY -> library.selected()?.let { openDocument(documents[it]) }
            View.READER -> togglePlayback()
        }
    }

    private fun renderLibrary() {
        val lang = settings.language
        val card = if (documents.isEmpty()) {
            NexusCard(
                title = "Lume",
                lines = listOf(Strings.emptyLibrary(lang)),
                footer = Strings.libraryEmptyFooter(lang),
                contentKey = shortHash("empty"),
                handlesBack = true,
            )
        } else {
            val selected = library.selectedIndex
            val rows = rowWindow(documents.size, selected)
            val lines = rows.map { i ->
                val doc = documents[i]
                val marker = if (i == selected) ">" else " "
                val pct = if (doc.totalWords > 0) doc.progressWordIndex * 100 / doc.totalWords else 0
                "$marker ${doc.title}  (${doc.source} · $pct%)".take(MAX_LINE_CHARS)
            }
            NexusCard(
                title = Strings.libraryTitle(lang),
                lines = lines,
                footer = Strings.libraryFooter(lang),
                contentKey = shortHash("lib:${documents.size}:$selected:${documents.getOrNull(selected)?.id}"),
                handlesBack = true,
            )
        }
        host.showCard(card, consumeShow())
    }

    /** A visible fallback card so the reader never silently fails to open. */
    private fun renderMessage(message: String) {
        host.showCard(
            NexusCard(
                title = "Lume",
                lines = listOf(message),
                footer = Strings.libraryEmptyFooter(settings.language),
                contentKey = shortHash("msg:$message"),
                handlesBack = true,
            ),
            consumeShow(),
        )
    }

    /* ---------------- reader view ---------------- */

    private fun openDocument(doc: DocumentInfo) {
        val words = store.words(doc.id)
        if (words.isEmpty()) {
            renderMessage(Strings.cannotOpen(settings.language))
            return
        }
        docId = doc.id
        docTitle = doc.title
        wpm = settings.lastWpm
        val m = ReaderModel(words, wpm)
        model = m
        val resume = doc.progressWordIndex.coerceIn(0, (m.size - 1).coerceAtLeast(0))
        anchorPosMs = m.posMsForWord(resume)
        playing = false // open paused; a tap starts playback
        sentAtElapsed = SystemClock.elapsedRealtime()
        windowRange = m.windowRange(resume)
        view = View.READER
        renderReader()
        // No ticker until playback starts.
    }

    private fun togglePlayback() {
        val m = model ?: return
        val cur = currentIndex(m)
        anchorPosMs = m.posMsForWord(cur)
        playing = !playing
        sentAtElapsed = SystemClock.elapsedRealtime()
        host.updateTimedLinesAnchor(contentKey, anchor())
        persistProgress()
        if (playing) startTicker() else stopTicker()
    }

    private fun changeSpeed(delta: Int) {
        val m = model ?: return
        val cur = currentIndex(m)
        m.setWpm(m.wpm + delta)
        wpm = m.wpm
        settings.lastWpm = wpm
        anchorPosMs = m.posMsForWord(cur)
        sentAtElapsed = SystemClock.elapsedRealtime()
        // Timeline changed for the whole window -> resend it (new contentKey).
        windowRange = m.windowRange(cur)
        renderReader()
    }

    private fun stepSentence(dir: Int) {
        val m = model ?: return
        val cur = currentIndex(m)
        val target = m.sentenceStep(cur, dir)
        anchorPosMs = m.posMsForWord(target)
        sentAtElapsed = SystemClock.elapsedRealtime()
        // playing stays false (sentence stepping is a paused action)
        if (target in windowRange) {
            host.updateTimedLinesAnchor(contentKey, anchor())
        } else {
            windowRange = m.windowRange(target)
            renderReader()
        }
        persistProgress()
    }

    private fun onProgressTick() {
        val m = model ?: return
        val cur = currentIndex(m)
        if (cur >= m.size - 1) {
            // Reached the end: freeze on the last word.
            playing = false
            anchorPosMs = m.posMsForWord(m.size - 1)
            sentAtElapsed = SystemClock.elapsedRealtime()
            host.updateTimedLinesAnchor(contentKey, anchor())
            persistProgress()
            stopTicker()
            return
        }
        maybePersist()
        ensureWindow(cur)
    }

    /** Pages the buffer forward when the read head nears the end of the window. */
    private fun ensureWindow(current: Int) {
        val m = model ?: return
        val needsSlide = current < windowRange.first || current >= windowRange.last - WINDOW_MARGIN
        if (!needsSlide) return
        reanchorToNow(m)
        windowRange = m.windowRange(current)
        renderReader()
    }

    private fun renderReader() {
        val m = model ?: return
        if (windowRange.isEmpty()) windowRange = m.windowRange(currentIndex(m))
        val baseMs = m.posMsForWord(windowRange.first)
        // Small window with per-window relative times, kept well under the 3 KiB
        // CXR control-message ceiling so it survives on a CXR-only link.
        val emitted = boundedLines(m, windowRange, baseMs)
        windowRange = windowRange.first until (windowRange.first + emitted.size)
        contentKey = shortHash("rd:$docId:$wpm:${windowRange.first}:${windowRange.last}")
        val lang = settings.language
        val cur = currentIndex(m)
        val pct = if (m.size > 0) cur * 100 / m.size else 0
        val surface = NexusTimedLines(
            title = docTitle.ifBlank { "Lume" }.take(READER_TITLE_CHARS),
            contentKey = contentKey,
            lines = emitted,
            anchor = anchor(baseMs),
            subtitle = "$wpm wpm · $pct%",
            footer = if (playing) Strings.readerPlayingFooter(lang) else Strings.readerPausedFooter(lang),
        )
        host.showTimedLines(surface, consumeShow())
    }

    /** Accumulates window words into relative-timed lines up to a CXR-safe byte budget. */
    private fun boundedLines(m: ReaderModel, range: IntRange, baseMs: Long): List<NexusTimedLine> {
        val out = ArrayList<NexusTimedLine>()
        var bytes = 0
        for (tw in m.timedWords(range)) {
            val text = tw.text.take(MAX_LINE_CHARS)
            val lineBytes = text.toByteArray(Charsets.UTF_8).size + PER_LINE_OVERHEAD_BYTES
            if (out.isNotEmpty() && (bytes + lineBytes > LINE_BYTE_BUDGET || out.size >= WINDOW_WORDS)) break
            out.add(NexusTimedLine((tw.timeMs - baseMs).coerceAtLeast(0L), text))
            bytes += lineBytes
        }
        return out
    }

    private fun anchor(baseMs: Long = currentBaseMs()): NexusPlaybackAnchor =
        NexusPlaybackAnchor(
            positionMs = (anchorPosMs - baseMs).coerceAtLeast(0L),
            playing = playing,
            sentAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )

    private fun currentBaseMs(): Long {
        val m = model ?: return 0L
        if (windowRange.isEmpty()) return 0L
        return m.posMsForWord(windowRange.first)
    }

    /** Freezes the anchor at the current predicted position (before re-windowing while playing). */
    private fun reanchorToNow(m: ReaderModel) {
        anchorPosMs = predictedPosMs(m)
        sentAtElapsed = SystemClock.elapsedRealtime()
    }

    private fun predictedPosMs(m: ReaderModel): Long {
        val maxMs = m.posMsForWord(m.size - 1)
        val pos = if (playing) {
            anchorPosMs + (SystemClock.elapsedRealtime() - sentAtElapsed).coerceAtLeast(0L)
        } else {
            anchorPosMs
        }
        return pos.coerceIn(0L, maxMs)
    }

    /** Current word index derived from the playback clock (no back-channel needed). */
    private fun currentIndex(m: ReaderModel): Int = m.wordIndexAt(predictedPosMs(m))

    private fun maybePersist() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersistElapsed >= PERSIST_INTERVAL_MS) persistProgress()
    }

    private fun persistProgress() {
        val m = model ?: return
        if (docId.isNotEmpty()) {
            store.updateProgress(docId, currentIndex(m))
            lastPersistElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun consumeShow(): Boolean {
        val show = !shown
        shown = true
        return show
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun rowWindow(count: Int, selected: Int): IntRange {
        if (count <= MAX_LINES) return 0 until count
        val start = (selected - MAX_LINES / 2).coerceIn(0, count - MAX_LINES)
        return start until (start + MAX_LINES)
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(16) {
            for (index in 0 until 8) {
                val b = digest[index].toInt() and 0xff
                append(hex[b ushr 4]); append(hex[b and 0x0f])
            }
        }
    }

    private companion object {
        const val TICK_MS = 750L
        const val PERSIST_INTERVAL_MS = 4_000L
        // Buffer sizing: kept small so each surface payload stays under the 3 KiB
        // CXR control-message ceiling (works without the SPP data plane).
        const val WINDOW_WORDS = 60
        const val WINDOW_MARGIN = 18
        const val LINE_BYTE_BUDGET = 1_900
        const val PER_LINE_OVERHEAD_BYTES = 26
        const val MAX_TITLE_CHARS = 120
        const val READER_TITLE_CHARS = 60
        const val MAX_LINE_CHARS = 240
        const val MAX_LINES = 64
    }
}
