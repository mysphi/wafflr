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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shows where Wafflr is reading, on top of the other app, without taking touches:
 *
 *  - a soft yellow box around the paragraph (or drawn area) being read. Text that came from the
 *    app itself is looked up again when the page scrolls, so the box moves with it;
 *  - a subtle underline under the word being spoken, where the app can say where its words are
 *    (most apps and web pages can). Word timing is estimated from each word's length, since the
 *    voices don't report it.
 *
 * Text read from a picture of the screen can't be followed: its box hides once the page moves.
 */
class Highlighter(
    private val context: Context,
    private val prefs: Prefs,
    private val reader: Reader,
) : ReaderListener {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val view = HighlightView(context)
    private var added = false

    private var state = ReaderState.IDLE
    private var lostForThisRead = false // picture-based text and the page moved
    private var spot: SentenceSpot? = null
    private var box: Rect? = null
    private var boxStale = true
    private var word: IntRange? = null
    private var wordRects: List<RectF> = emptyList()

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
            if (state != ReaderState.IDLE) main.postDelayed(this, if (state == ReaderState.PLAYING) 70 else 250)
        }
    }

    override fun onStatus(status: ReaderStatus) {
        val was = state
        state = status.state
        if (was == ReaderState.IDLE && state != ReaderState.IDLE) {
            lostForThisRead = false
            spot = null
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
        if (state == ReaderState.IDLE) {
            main.removeCallbacks(ticker)
            hide()
        }
    }

    /** The page scrolled or changed: look the text up again (or give up, for picture text). */
    fun pageMoved() {
        if (state == ReaderState.IDLE) return
        if (spot?.spot?.node == null) {
            lostForThisRead = true
            hide()
        } else {
            boxStale = true
        }
    }

    private fun tick() {
        if (!prefs.highlight || state == ReaderState.PREPARING) { hide(); return }
        val now = reader.nowPlaying() ?: return
        val s = now.spot ?: run { hide(); return }
        if (s !== spot) {
            spot = s
            boxStale = true
            word = null
        }
        val node = s.spot.node
        if (node == null && lostForThisRead) { hide(); return }

        var moved = false
        if (boxStale) {
            boxStale = false
            box = if (node == null) s.spot.rect else currentBounds(node)
            moved = true
        }
        val b = box ?: run { hide(); return }

        // The word being spoken, located in the app's text.
        if (prefs.underlineWords && node != null && s.charStart >= 0) {
            val w = wordAt(now.sentence, now.fraction)
            if (w != word || moved) {
                word = w
                wordRects = if (w == null) emptyList() else charRects(node, s.charStart + w.first, w.last - w.first + 1)
            }
        } else {
            wordRects = emptyList()
        }
        show(b, wordRects)
    }

    private fun currentBounds(node: AccessibilityNodeInfo): Rect? = try {
        if (!node.refresh() || !node.isVisibleToUser) null
        else Rect().also { node.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
    } catch (_: Throwable) {
        null
    }

    /** Screen boxes of characters [start, start+length) in the node's text, if the app says. */
    private fun charRects(node: AccessibilityNodeInfo, start: Int, length: Int): List<RectF> = try {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (node.availableExtraData?.contains(key) != true) emptyList() else {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            }
            node.refreshWithExtraData(key, args)
            @Suppress("DEPRECATION")
            node.extras.getParcelableArray(key)?.mapNotNull { it as? RectF }?.filter { !it.isEmpty }.orEmpty()
        }
    } catch (_: Throwable) {
        emptyList()
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
        private val WORD = Regex("\\S+")

        /**
         * Which word of [sentence] is being spoken when [fraction] of its audio has played.
         * Longer words take longer to say, and punctuation adds a pause.
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
