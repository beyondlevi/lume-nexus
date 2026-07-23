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
 * Ring mapping (R08 Access Bridge), mirroring the original touchpad:
 *  - LIBRARY: NEXT/PREV move selection, SELECT opens the focused document,
 *    BACK exits the plugin.
 *  - READER:  SELECT toggles play/pause; while playing NEXT/PREV change speed;
 *    while paused NEXT/PREV step sentences; BACK exits the plugin.
 *
 * Platform note: a timed-lines surface cannot intercept BACK (the hub hides it
 * on BACK), so — like the shipped Lyrics plugin — BACK from the reader closes
 * the plugin rather than returning to the library. Re-open Lume to pick another
 * document.
 *
 * Surface protocol (matches the shipped Transit/Lyrics plugins): a single
 * surface id, shown once per session and updated thereafter; the card->timed
 * transition goes through updateTimedLines, and play/pause/seek go through the
 * anchor-only update.
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

    // Reader session state.
    private var model: ReaderModel? = null
    private var docId: String = ""
    private var docTitle: String = ""
    private var wpm: Int = RsvpEngine.DEFAULT_WPM
    private var anchorPosMs: Long = 0L
    private var playing: Boolean = false
    private var sentAtElapsed: Long = 0L
    private var windowRange: IntRange = IntRange.EMPTY
    private var contentKey: String = ""

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
        playing = true
        sentAtElapsed = SystemClock.elapsedRealtime()
        windowRange = m.windowRange(resume)
        view = View.READER
        renderReader()
        startTicker()
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
        ensureWindow(cur, forceResend = true)
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
            ensureWindow(target, forceResend = true)
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
        persistProgress()
        ensureWindow(cur, forceResend = false)
    }

    /** Slides the surface window when the read head nears its end (or falls outside it). */
    private fun ensureWindow(current: Int, forceResend: Boolean) {
        val m = model ?: return
        val needsSlide = forceResend ||
            current < windowRange.first ||
            current >= windowRange.last - WINDOW_MARGIN
        if (!needsSlide) return
        windowRange = m.windowRange(current)
        renderReader()
    }

    private fun renderReader() {
        val m = model ?: return
        if (windowRange.isEmpty()) windowRange = m.windowRange(currentIndex(m))
        // Build the timed lines within a byte budget so the surface payload never
        // exceeds the hub's 64 KiB ceiling (which would reject the whole surface).
        val emitted = boundedLines(m, windowRange)
        windowRange = windowRange.first until (windowRange.first + emitted.size)
        contentKey = shortHash("rd:$docId:$wpm:${windowRange.first}:${windowRange.last}")
        val lang = settings.language
        val cur = currentIndex(m)
        val pct = if (m.size > 0) cur * 100 / m.size else 0
        val surface = NexusTimedLines(
            title = docTitle.ifBlank { "Lume" }.take(MAX_TITLE_CHARS),
            contentKey = contentKey,
            lines = emitted,
            anchor = anchor(),
            subtitle = "$wpm wpm · $pct%",
            footer = if (playing) Strings.readerPlayingFooter(lang) else Strings.readerPausedFooter(lang),
        )
        host.showTimedLines(surface, consumeShow())
    }

    /** Accumulates window words into timed lines up to a safe byte budget and line cap. */
    private fun boundedLines(m: ReaderModel, range: IntRange): List<NexusTimedLine> {
        val out = ArrayList<NexusTimedLine>()
        var bytes = SURFACE_OVERHEAD_BYTES
        for (tw in m.timedWords(range)) {
            val text = tw.text.take(MAX_LINE_CHARS)
            val lineBytes = text.toByteArray(Charsets.UTF_8).size + PER_LINE_OVERHEAD_BYTES
            if (out.isNotEmpty() && (bytes + lineBytes > SURFACE_BYTE_BUDGET || out.size >= MAX_TIMED_LINES)) break
            out.add(NexusTimedLine(tw.timeMs, text))
            bytes += lineBytes
        }
        return out
    }

    private fun anchor(): NexusPlaybackAnchor =
        NexusPlaybackAnchor(
            positionMs = anchorPosMs.coerceAtLeast(0L),
            playing = playing,
            sentAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )

    /** Current word index derived from the playback clock (no back-channel needed). */
    private fun currentIndex(m: ReaderModel): Int {
        val pos = if (playing) {
            anchorPosMs + (SystemClock.elapsedRealtime() - sentAtElapsed).coerceAtLeast(0L)
        } else {
            anchorPosMs
        }
        return m.wordIndexAt(pos)
    }

    private fun persistProgress() {
        val m = model ?: return
        if (docId.isNotEmpty()) store.updateProgress(docId, currentIndex(m))
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
        const val TICK_MS = 4_000L
        const val WINDOW_MARGIN = 120
        const val MAX_TITLE_CHARS = 120
        const val MAX_LINE_CHARS = 240
        const val MAX_LINES = 64
        const val MAX_TIMED_LINES = 2_000
        // Keep the timed-lines payload comfortably under the hub's 64 KiB ceiling.
        const val SURFACE_BYTE_BUDGET = 48_000
        const val SURFACE_OVERHEAD_BYTES = 400
        const val PER_LINE_OVERHEAD_BYTES = 26
    }
}
