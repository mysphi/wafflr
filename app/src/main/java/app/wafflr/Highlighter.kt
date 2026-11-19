package app.wafflr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shows where Wafflr is reading, on top of the other app, without taking touches:
 *
 *  - a soft yellow box around the paragraph (or drawn area) being read;
 *  - a subtle underline under the word being spoken. The voices don't report word timings, so
 *    [WordTimer] works them out from each sentence's audio. Where the word is on screen comes
 *    from the app if it says (most apps do); otherwise (Gmail's emails, for one) the paragraph
 *    is read from a screenshot to find its words.
 *
 * Scrolling: while the page moves, the box moves by each scroll's distance as it happens. Apps
 * often report stale positions mid-scroll, so the box is only checked against the app once
 * scrolling settles. Text read from a picture of the screen can't be followed: its box hides
 * once the page moves.
 */
class Highlighter(
    private val context: Context,
    private val prefs: Prefs,
    private val reader: Reader,
    /** Finds the words inside a screen area from a screenshot (null if it can't). */
    private val findWords: (Rect, (List<Pair<String, Rect>>?) -> Unit) -> Unit,
) : ReaderListener {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val view = HighlightView(context)
    private var added = false

    private var state = ReaderState.IDLE
    val active: Boolean get() = state != ReaderState.IDLE && prefs.highlight
    private var lostForThisRead = false // picture-based text and the page moved
    private var spot: SentenceSpot? = null
    private var box: Rect? = null           // where the box is drawn, in screen pixels
    private var needsCheck = true           // look the box up in the app again when settled
    private var lastCheck = 0L
    private var unseen = 0                  // checks in a row that found the text off screen
    private var lastScroll = 0L             // last scroll whose distance was known
    private var followUntil = 0L            // re-check every frame until then (scrolls of unknown distance)
    private var live: AccessibilityNodeInfo? = null // the current paragraph's node, found again if the app replaced it
    private var trustScrolls = true         // the app's scroll distances match where its text ends up
    private var movedUnchecked = false      // the box was moved by scrolls and not yet checked
    private var scrolledThisRead = false
    private var locating = false            // looking for the paragraph in a screenshot
    private var locateRetryAt = 0L
    private var scrollSeq = 0               // counts page movements, so a screenshot taken before one is ignored
    private var needsLocate = false         // find the paragraph in a screenshot once the page settles
    /** Whether the app's places for its text are right after scrolling: null until checked against a screenshot. */
    private var appTrusted: Boolean? = null

    /** Hides everything (while the phone is locked). */
    var suppressed = false
        set(value) {
            field = value
            if (value) main.post { hide() }
        }

    private var wordKey = -1                // sentence index * 10000 + word index, for the current underline
    private var wordRects: List<RectF> = emptyList()
    private var charsUnsupported = false    // the app can't say where its characters are

    // Words found from a screenshot, for apps that can't say where their words are.
    private var ocrSpot: SentenceSpot? = null
    private var ocrState = OCR_NONE
    private var ocrRetryAt = 0L
    private var ocrBox: Rect? = null        // the box when the screenshot was read
    private var ocrWords: List<Pair<String, RectF>> = emptyList() // relative to ocrBox's corner
    private var alignedFor = -1
    private var aligned: IntArray = IntArray(0)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (state == ReaderState.IDLE) return
            val now = SystemClock.uptimeMillis()
            val delay = when {
                now < followUntil || now - lastScroll < SETTLE_MS -> FRAME_MS
                state == ReaderState.PLAYING -> 50L
                else -> 250L
            }
            main.postDelayed(this, delay)
        }
    }

    override fun onStatus(status: ReaderStatus) {
        val was = state
        state = status.state
        if (was == ReaderState.IDLE && state != ReaderState.IDLE) {
            lostForThisRead = false
            spot = null
            live = null
            trustScrolls = true
            movedUnchecked = false
            scrolledThisRead = false
            needsLocate = false
            appTrusted = null
            ocrSpot = null
            ocrState = OCR_NONE
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
        if (state == ReaderState.IDLE) {
            main.removeCallbacks(ticker)
            hide()
        }
    }

    /**
     * The page scrolled by [dx], [dy] pixels (-1 or 0,0 when the app doesn't say) inside [area],
     * or the window changed.
     *
     * The first time, the box hides and the paragraph is found in a screenshot once the page
     * settles; that also shows whether the app's own places for its text can be trusted. If they
     * can, later scrolls move the box along live; if not (Gmail's emails), the box hides during
     * each scroll and is found from a screenshot again afterwards.
     */
    fun pageMoved(dx: Int = 0, dy: Int = 0, area: Rect? = null) {
        if (state == ReaderState.IDLE) return
        val s = spot ?: return
        val b = box
        if (b != null && area != null && !Rect.intersects(area, b)) return // another part of the screen scrolled
        val now = SystemClock.uptimeMillis()
        scrollSeq++
        scrolledThisRead = true
        val known = (dx != 0 || dy != 0) && dx != -1 && dy != -1
        if (known) lastScroll = now else followUntil = now + FOLLOW_MS
        if (s.spot.node == null) {
            // Text read from a picture: find it again once the page settles.
            lostForThisRead = true
            box = null
            needsLocate = true
            hide()
        } else if (appTrusted == true && trustScrolls) {
            if (known && b != null) {
                box = Rect(b).apply { offset(-dx, -dy) }
                wordRects = wordRects.map { RectF(it).apply { offset(-dx.toFloat(), -dy.toFloat()) } }
                movedUnchecked = true
                if (added) show(box!!, wordRects)
            }
        } else {
            box = null
            needsLocate = true
            hide()
        }
        needsCheck = true
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun tick() {
        if (suppressed || !prefs.highlight || state == ReaderState.PREPARING) { hide(); return }
        val now = reader.nowPlaying() ?: return
        val s = now.spot ?: run { hide(); return }
        val node = s.spot.node
        val t = SystemClock.uptimeMillis()
        if (s !== spot) {
            val samePiece = spot?.spot === s.spot
            spot = s
            wordKey = -1
            wordRects = emptyList()
            if (!samePiece) {
                charsUnsupported = appTrusted == false
                live = node
                box = if (node == null && !lostForThisRead) s.spot.rect else null
                needsCheck = node != null
                movedUnchecked = false
                unseen = 0
                // After scrolling, a new paragraph's place has to be found the same way.
                needsLocate = scrolledThisRead && (node == null || appTrusted != true)
            }
        }
        val scrolling = t - lastScroll < SETTLE_MS
        val settled = !scrolling && t >= followUntil

        if (needsLocate) {
            box = null
            if (settled) locate(s, live ?: node, now.sentence)
        } else if (node != null && appTrusted != false && !scrolling &&
            (needsCheck || t < followUntil || t - lastCheck > RECHECK_MS)
        ) {
            // Check the box against the app, except mid-scroll (apps report stale places then).
            needsCheck = false
            lastCheck = t
            var found = live?.let { check(it) }
            if (found == null || found == OFF_SCREEN) {
                // The app may have replaced the paragraph's node (web pages and lists do): find it again.
                refind(node)?.let { n -> check(n)?.takeIf { it != OFF_SCREEN }?.let { live = n; found = it } }
            }
            val f = found?.takeIf { it != OFF_SCREEN }
            val b0 = box
            when {
                f != null && movedUnchecked && b0 != null &&
                    (kotlin.math.abs(b0.top - f.top) > 24 * density || kotlin.math.abs(b0.left - f.left) > 24 * density) -> {
                    // Scrolling and the app disagree about where the text is: settle it with a screenshot.
                    trustScrolls = false
                    box = null
                    needsLocate = true
                }
                f != null -> {
                    unseen = 0
                    if (f != box) {
                        box = f
                        wordKey = -1 // look the word up again at the new place
                        if (b0 != null) shiftOcr(b0, f)
                    }
                }
                found == OFF_SCREEN -> if (++unseen >= 3) { box = null; if (scrolledThisRead) needsLocate = true }
                scrolledThisRead -> { box = null; needsLocate = true }
                box == null -> box = Rect(s.spot.rect)
            }
            movedUnchecked = false
        }
        val b = box ?: run { hide(); return }

        if (prefs.underlineWords) underline(now, s, live ?: node, b, scrolling) else wordRects = emptyList()
        show(b, wordRects)
    }

    /** Finds the word being spoken and where it is on screen. */
    private fun underline(now: NowPlaying, s: SentenceSpot, node: AccessibilityNodeInfo?, b: Rect, scrolling: Boolean) {
        val words = WORD.findAll(now.sentence).map { it.range }.toList()
        val index = now.plan?.indexAt(now.frame) ?: wordAt(now.sentence, now.fraction)?.let { words.indexOf(it) }
        if (index == null || index < 0 || index >= words.size) { wordRects = emptyList(); return }
        val key = now.index * 10000 + index
        if (key == wordKey) return

        // From the app, if it says where its characters are.
        if (node != null && s.charStart >= 0 && !charsUnsupported && !scrolling) {
            val w = words[index]
            val rects = charRects(node, s.charStart + w.first, w.last - w.first + 1, b)
            if (rects != null) {
                wordKey = key
                wordRects = rects
                return
            }
            charsUnsupported = true
        }
        if (charsUnsupported || node == null || s.charStart < 0) {
            // From a screenshot of the paragraph.
            wordRects = ocrWord(now, index, b) ?: emptyList()
            if (ocrState == OCR_DONE) wordKey = key
        }
    }

    /** The word's place from the screenshot's words, starting a screenshot read if needed. */
    private fun ocrWord(now: NowPlaying, index: Int, b: Rect): List<RectF>? {
        val s = now.spot ?: return null
        if (ocrSpot?.spot !== s.spot) {
            ocrSpot = s
            ocrState = OCR_NONE
            alignedFor = -1
        }
        val t = SystemClock.uptimeMillis()
        if (ocrState == OCR_NONE && t >= ocrRetryAt && t - lastScroll > SETTLE_MS && t >= followUntil) {
            ocrState = OCR_PENDING
            val target = s
            val at = Rect(b)
            findWords(at) { found ->
                main.post {
                    if (ocrSpot !== target && ocrSpot?.spot !== target.spot) return@post
                    if (found == null) {
                        ocrState = OCR_NONE
                        ocrRetryAt = SystemClock.uptimeMillis() + 1000
                        return@post
                    }
                    ocrBox = at
                    ocrWords = found.map { (text, r) ->
                        text to RectF(r).apply { offset(-at.left.toFloat(), -at.top.toFloat()) }
                    }
                    alignedFor = -1
                    ocrState = OCR_DONE
                    wordKey = -1
                }
            }
        }
        if (ocrState != OCR_DONE) return null
        if (alignedFor != now.index) {
            alignedFor = now.index
            aligned = align(WORD.findAll(now.sentence).map { it.value }.toList(), ocrWords.map { it.first })
        }
        val j = aligned.getOrNull(index) ?: return null
        if (j < 0) return null
        val r = ocrWords[j].second
        return listOf(RectF(r).apply { offset(b.left.toFloat(), b.top.toFloat()) })
    }

    /** The box moved from [old] to [new]: the screenshot words move with it, or are re-read if it changed shape. */
    private fun shiftOcr(old: Rect, new: Rect) {
        if (ocrState == OCR_PENDING) return
        if (kotlin.math.abs(old.width() - new.width()) > 4 || kotlin.math.abs(old.height() - new.height()) > 4) {
            ocrState = OCR_NONE
        }
    }

    /**
     * Finds the paragraph on screen from a screenshot, for apps that won't say where their text
     * went after a scroll. The box goes around the paragraph's words that are visible, and the
     * words found are kept for the underline.
     */
    private fun locate(s: SentenceSpot, node: AccessibilityNodeInfo?, sentence: String) {
        val t = SystemClock.uptimeMillis()
        if (locating || t < locateRetryAt) return
        locating = true
        val scrollAt = scrollSeq
        val screen = try {
            wm.currentWindowMetrics.bounds
        } catch (_: Throwable) {
            Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        }
        val text = node?.text?.toString()?.takeIf { it.isNotBlank() } ?: sentence
        findWords(screen) { found ->
            main.post {
                locating = false
                // Gone stale: the paragraph changed, or the page moved again.
                if (spot?.spot !== s.spot || scrollSeq != scrollAt || state == ReaderState.IDLE) return@post
                val placed = found?.let { placeAmong(text, it) }
                if (placed == null) {
                    locateRetryAt = SystemClock.uptimeMillis() + LOCATE_RETRY_MS
                    return@post
                }
                val (b, inside) = placed
                needsLocate = false
                lostForThisRead = false
                movedUnchecked = false
                lastCheck = SystemClock.uptimeMillis()
                // Does the app agree about where its text is? Its paragraph box starts at or
                // just above the first visible line found here.
                val n = live
                if (n != null && appTrusted == null) {
                    val app = check(n)?.takeIf { it != OFF_SCREEN }
                    appTrusted = app != null && kotlin.math.abs(app.top - b.top) <= 40 * density &&
                        kotlin.math.abs(app.left - b.left) <= 40 * density
                }
                box = b
                ocrSpot = s
                ocrBox = b
                ocrWords = inside.map { (w, r) -> w to RectF(r).apply { offset(-b.left.toFloat(), -b.top.toFloat()) } }
                ocrState = OCR_DONE
                alignedFor = -1
                if (n == null || appTrusted == false) charsUnsupported = true // the app's word places are stale too
                wordKey = -1
                main.removeCallbacks(ticker)
                main.post(ticker)
            }
        }
    }

    /** The box around [text]'s words among the screen's [words], and the screen words inside it. */
    private fun placeAmong(text: String, words: List<Pair<String, Rect>>): Pair<Rect, List<Pair<String, Rect>>>? {
        val mine = WORD.findAll(text).map { it.value }.toList()
        val map = align(mine, words.map { it.first })
        val hits = map.filter { it >= 0 }
        if (hits.size < minOf(3, mine.size)) return null
        val box = Rect(words[hits.first()].second)
        for (j in hits.first()..hits.last()) box.union(words[j].second)
        val inside = (hits.first()..hits.last()).map { words[it] }
        return box to inside
    }

    /** Looks for the paragraph's text again in the window, nearest to where the box was. */
    private fun refind(old: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
        val text = old.text?.toString()
        val root = (context as? android.accessibilityservice.AccessibilityService)?.rootInActiveWindow
        if (text.isNullOrBlank() || root == null) null else {
            val near = box ?: spot?.spot?.rect
            val want = text.replace(Regex("\\s+"), " ").trim()
            root.findAccessibilityNodeInfosByText(want.take(40))
                .filter { it.isVisibleToUser && it.text?.toString()?.replace(Regex("\\s+"), " ")?.trim() == want }
                .minByOrNull { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (near == null) 0 else kotlin.math.abs(r.top - near.top) + kotlin.math.abs(r.left - near.left)
                }
        }
    } catch (_: Throwable) {
        null
    }

    /** The node's place now; [OFF_SCREEN] if it has scrolled away; null if the app didn't answer. */
    private fun check(node: AccessibilityNodeInfo): Rect? = try {
        if (!node.refresh()) null
        else if (!node.isVisibleToUser) OFF_SCREEN
        else Rect().also { node.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
    } catch (_: Throwable) {
        null
    }

    /**
     * Screen boxes of characters [start, start+length) in the node's text, moved to line up with
     * the box [b] as drawn. Null if the app can't say where its characters are.
     */
    private fun charRects(node: AccessibilityNodeInfo, start: Int, length: Int, b: Rect): List<RectF>? = try {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (node.availableExtraData?.contains(key) != true) null else {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            }
            node.refreshWithExtraData(key, args)
            @Suppress("DEPRECATION")
            val rects = node.extras.getParcelableArray(key)?.mapNotNull { it as? RectF }?.filter { !it.isEmpty }.orEmpty()
            if (rects.isEmpty()) null else {
                val at = Rect().also { node.getBoundsInScreen(it) }
                val dx = (b.left - at.left).toFloat()
                val dy = (b.top - at.top).toFloat()
                rects.map { RectF(it).apply { offset(dx, dy) } }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun show(b: Rect, words: List<RectF>) {
        if (!added) {
            try { wm.addView(view, params); added = true } catch (_: Throwable) { return }
        }
        view.target = b
        view.words = words
        view.invalidate()
    }

    fun hide() {
        if (!added) return
        try { wm.removeView(view) } catch (_: Throwable) {}
        added = false
    }

    private inner class HighlightView(context: Context) : View(context) {
        var target: Rect? = null
        var words: List<RectF> = emptyList()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFD54F") }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFFB300")
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
        }
        private val underline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFA000")
            strokeWidth = 2.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            val dx = -loc[0].toFloat()
            val dy = -loc[1].toFloat()
            target?.let { r ->
                val pad = 4 * density
                val box = RectF(r.left + dx - pad, r.top + dy - pad, r.right + dx + pad, r.bottom + dy + pad)
                val corner = 8 * density
                canvas.drawRoundRect(box, corner, corner, fill)
                canvas.drawRoundRect(box, corner, corner, edge)
            }
            // One underline per line the word sits on (a word can wrap).
            words.groupBy { (it.bottom / (4 * density)).toInt() }.values.forEach { line ->
                val left = line.minOf { it.left } + dx
                val right = line.maxOf { it.right } + dx
                val y = line.maxOf { it.bottom } + dy + 2 * density
                canvas.drawLine(left, y, right, y, underline)
            }
        }
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val FOLLOW_MS = 500L
        private const val SETTLE_MS = 250L
        private const val RECHECK_MS = 1000L
        private const val LOCATE_RETRY_MS = 1500L
        private const val OCR_NONE = 0
        private const val OCR_PENDING = 1
        private const val OCR_DONE = 2
        private val OFF_SCREEN = Rect(-1, -1, -1, -1)
        private val WORD = Regex("\\S+")

        private fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

        private fun same(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b) return true
            if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
            if (a.length >= 4 && kotlin.math.abs(a.length - b.length) <= 1) {
                // One letter misread.
                var diff = 0
                var i = 0
                var j = 0
                while (i < a.length && j < b.length) {
                    if (a[i] == b[j]) { i++; j++; continue }
                    diff++
                    if (diff > 1) return false
                    when {
                        a.length > b.length -> i++
                        b.length > a.length -> j++
                        else -> { i++; j++ }
                    }
                }
                return diff + (a.length - i) + (b.length - j) <= 1
            }
            return false
        }

        /**
         * Lines the sentence's words up with the words read from the screen. For each sentence
         * word, the index of its screen word, or -1. Tries each place the sentence could start
         * and keeps the one that matches the most words.
         */
        fun align(sentence: List<String>, screen: List<String>): IntArray {
            val a = sentence.map(::norm)
            val b = screen.map(::norm)
            var best = IntArray(a.size) { -1 }
            var bestCount = 0
            // Any word can start the match (the paragraph's start may be scrolled out of view),
            // but short words are too common to start one, unless they open the sentence.
            val anchors = a.indices.filter { a[it].length >= 3 || (it < 3 && a[it].isNotEmpty()) }
            for (j0 in b.indices) for (i0 in anchors) {
                if (!same(a[i0], b[j0])) continue
                val map = IntArray(a.size) { -1 }
                var j = j0
                var count = 0
                for (i in i0 until a.size) {
                    if (a[i].isEmpty()) continue
                    val hit = (j until minOf(b.size, j + 6)).firstOrNull { same(a[i], b[it]) }
                    if (hit != null) { map[i] = hit; j = hit + 1; count++ }
                }
                if (count > bestCount) { best = map; bestCount = count }
            }
            return best
        }

        /**
         * A fallback guess, used until a sentence's word timings are ready: which word of
         * [sentence] is being spoken when [fraction] of its audio has played.
         */
        fun wordAt(sentence: String, fraction: Float): IntRange? {
            val words = WORD.findAll(sentence).toList()
            if (words.isEmpty()) return null
            fun weight(w: String): Float {
                var x = w.count { it.isLetterOrDigit() } + 1.5f
                when (w.last()) {
                    ',', ';', ':' -> x += 2f
                    '.', '!', '?', '…' -> x += 3f
                }
                return x
            }
            val weights = words.map { weight(it.value) }
            // The voice leaves a little silence at each end of a sentence.
            val f = ((fraction - 0.03f) / 0.92f).coerceIn(0f, 1f)
            var target = f * weights.sum()
            for ((i, w) in weights.withIndex()) {
                if (target < w) return words[i].range
                target -= w
            }
            return words.last().range
        }
    }
}
