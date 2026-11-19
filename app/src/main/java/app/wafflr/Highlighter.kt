package app.wafflr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.WindowManager

/**
 * Draws a soft yellow highlight over the paragraph (or drawn area) being read, on top of the
 * other app. It never takes touches. When the page scrolls, the highlight is hidden for the rest
 * of that read, since the text has moved.
 */
class Highlighter(private val context: Context, private val prefs: Prefs) : ReaderListener {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private val view = HighlightView(context)
    private var added = false
    private var lostForThisRead = false
    private var lastState = ReaderState.IDLE

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    override fun onStatus(status: ReaderStatus) {
        if (status.state == ReaderState.PREPARING || (status.state != ReaderState.IDLE && lastState == ReaderState.IDLE)) {
            lostForThisRead = false // a new read
        }
        lastState = status.state
        val region = status.region
        val show = prefs.highlight && !lostForThisRead && region != null &&
            (status.state == ReaderState.PLAYING || status.state == ReaderState.PAUSED)
        if (show) showAt(region!!) else hide()
    }

    /** The page scrolled or changed: the highlight would be in the wrong place. */
    fun pageMoved() {
        if (lastState == ReaderState.IDLE) return
        lostForThisRead = true
        hide()
    }

    private fun showAt(r: Rect) {
        if (!added) {
            try { wm.addView(view, params); added = true } catch (_: Throwable) { return }
        }
        view.target = r
        view.invalidate()
    }

    fun hide() {
        if (!added) return
        try { wm.removeView(view) } catch (_: Throwable) {}
        added = false
    }

    private inner class HighlightView(context: Context) : View(context) {
        var target: Rect? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFD54F") }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCFFB300")
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
        }
        private val loc = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            val r = target ?: return
            getLocationOnScreen(loc)
            val pad = 4 * density
            val box = RectF(r.left - loc[0] - pad, r.top - loc[1] - pad, r.right - loc[0] + pad, r.bottom - loc[1] + pad)
            val corner = 8 * density
            canvas.drawRoundRect(box, corner, corner, fill)
            canvas.drawRoundRect(box, corner, corner, edge)
        }
    }
}
