package app.wafflr

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating button drawn over every app, plus a small player that appears beside it while
 * reading.
 *
 *  - Tap: read the screen (or pause/resume while reading)
 *  - Long-press: draw around the text to read (or stop, while reading)
 *  - Drag: move it; it snaps to the nearest side
 */
class FloatingBubble(
    private val context: Context,
    private val prefs: Prefs,
    private val reader: Reader,
    private val onReadScreen: () -> Unit,
    private val onSelectArea: () -> Unit,
) : ReaderListener {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())

    private val bubble = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 22f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2D6CDF"))
            setStroke(dp(2), Color.WHITE)
        }
        elevation = dp(6).toFloat()
        contentDescription = "Wafflr: read screen"
    }

    private val progress = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(4), 0)
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor("#E6202124"))
        }
        setPadding(dp(6), dp(4), dp(6), dp(4))
        visibility = View.GONE
        addView(progress)
        addView(control("⏮", "Previous sentence") { reader.previous() })
        addView(control("⏭", "Next sentence") { reader.next() })
        addView(control("■", "Stop") { reader.stop() })
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private var shown = false

    fun show() {
        if (shown) return
        val size = dp(56)
        arrange()
        root.removeAllViews()
        val panelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (prefs.bubbleRight) {
            panelLp.rightMargin = dp(8)
            root.addView(panel, panelLp); root.addView(bubble, LinearLayout.LayoutParams(size, size))
        } else {
            panelLp.leftMargin = dp(8)
            root.addView(bubble, LinearLayout.LayoutParams(size, size)); root.addView(panel, panelLp)
        }
        bubble.setOnTouchListener(DragHandler())
        wm.addView(root, params)
        shown = true
        reader.addListener(this)
    }

    fun hide() {
        if (!shown) return
        reader.removeListener(this)
        try { wm.removeView(root) } catch (_: Throwable) {}
        shown = false
    }

    private fun arrange() {
        params.gravity = Gravity.TOP or if (prefs.bubbleRight) Gravity.END else Gravity.START
        params.x = dp(4)
        val screenH = wm.currentWindowMetrics.bounds.height()
        params.y = prefs.bubbleY.takeIf { it >= 0 } ?: (screenH * 0.45).toInt()
    }

    override fun onStatus(status: ReaderStatus) {
        bubble.text = when (status.state) {
            ReaderState.IDLE -> "🔊"
            ReaderState.PREPARING -> "…"
            ReaderState.PLAYING -> "❚❚"
            ReaderState.PAUSED -> "▶"
        }
        bubble.contentDescription = when (status.state) {
            ReaderState.IDLE -> "Wafflr: read screen"
            ReaderState.PLAYING -> "Pause reading"
            ReaderState.PAUSED -> "Resume reading"
            ReaderState.PREPARING -> "Wafflr is getting ready"
        }
        panel.visibility = if (status.state == ReaderState.IDLE) View.GONE else View.VISIBLE
        progress.text = when (status.state) {
            ReaderState.PREPARING -> status.message.ifEmpty { "Loading…" }
            ReaderState.IDLE -> ""
            else -> "${status.index + 1}/${status.total}"
        }
    }

    override fun onError(message: String) {
        android.widget.Toast.makeText(context, "Wafflr: ${message.lineSequence().first()}", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun onTap() {
        if (reader.isActive) reader.togglePause() else onReadScreen()
    }

    private fun onLongPress() {
        bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (reader.isActive) reader.stop() else onSelectArea()
    }

    /** Hides the button without removing it, e.g. while taking a screenshot. */
    fun setHidden(hidden: Boolean) {
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun control(glyph: String, label: String, action: () -> Unit) = TextView(context).apply {
        text = glyph
        contentDescription = label
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
        setOnClickListener { action() }
    }

    private inner class DragHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!dragging) { longPressed = true; onLongPress() }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startY = params.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        val screenW = wm.currentWindowMetrics.bounds.width()
                        val right = e.rawX > screenW / 2
                        prefs.bubbleY = params.y
                        if (right != prefs.bubbleRight) {
                            prefs.bubbleRight = right
                            hide(); show() // rebuild on the other side
                        }
                    } else if (!longPressed) {
                        v.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}
