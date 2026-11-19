package app.wafflr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** The area the user drew, in screen pixels. */
class SelectedArea(val path: Path, val bounds: Rect)

/**
 * A see-through layer over the whole screen where you drag a box, or draw a freehand shape,
 * around the text you want read.
 */
class AreaSelector(
    private val context: Context,
    private val prefs: Prefs,
    private val onDone: (SelectedArea?) -> Unit,
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var freehand = prefs.areaFreehand
    private var shown = false

    private val canvasView = DrawView(context)
    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val modeButton = pill("") { toggleMode() }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#E6202124"))
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(hint)
        addView(modeButton)
        addView(pill("✕ Cancel") { finish(null) })
    }
    private val root = FrameLayout(context).apply {
        addView(canvasView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
        })
    }

    fun show() {
        if (shown) return
        updateMode()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(root, params)
        shown = true
    }

    fun dismiss() {
        if (!shown) return
        try { wm.removeView(root) } catch (_: Throwable) {}
        shown = false
    }

    private fun finish(area: SelectedArea?) {
        dismiss()
        onDone(area)
    }

    private fun toggleMode() {
        freehand = !freehand
        prefs.areaFreehand = freehand
        canvasView.reset()
        updateMode()
    }

    private fun updateMode() {
        hint.text = if (freehand) "Draw around the text" else "Drag a box over the text"
        modeButton.text = if (freehand) "▭ Box instead" else "✎ Draw instead"
    }

    private fun pill(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { action() }
    }

    private inner class DrawView(context: Context) : View(context) {
        private val dim = Paint().apply { color = Color.parseColor("#59000000") }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2D6CDF")
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#332D6CDF")
            style = Paint.Style.FILL
        }
        private val path = Path()
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var drawing = false

        fun reset() { path.reset(); drawing = false; invalidate() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            if (!path.isEmpty) {
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    path.reset()
                    startX = e.x; startY = e.y; lastX = e.x; lastY = e.y
                    if (freehand) path.moveTo(e.x, e.y)
                    drawing = true
                }
                MotionEvent.ACTION_MOVE -> if (drawing) {
                    if (freehand) {
                        // Smooth the line a little.
                        path.quadTo(lastX, lastY, (e.x + lastX) / 2, (e.y + lastY) / 2)
                    } else {
                        path.reset()
                        path.addRect(minOf(startX, e.x), minOf(startY, e.y), maxOf(startX, e.x), maxOf(startY, e.y), Path.Direction.CW)
                    }
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> if (drawing) {
                    drawing = false
                    if (freehand) path.close()
                    val r = RectF()
                    @Suppress("DEPRECATION") path.computeBounds(r, true)
                    if (r.width() < dp(24) || r.height() < dp(16) || (!freehand && abs(e.x - startX) < dp(24))) {
                        // Too small: probably a stray tap. Keep the layer open.
                        reset()
                        return true
                    }
                    // Convert from this view's coordinates to screen coordinates.
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    val screenPath = Path(path).apply { offset(loc[0].toFloat(), loc[1].toFloat()) }
                    val bounds = Rect(
                        (r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(),
                        (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt(),
                    )
                    finish(SelectedArea(screenPath, bounds))
                }
                MotionEvent.ACTION_CANCEL -> reset()
            }
            return true
        }
    }
}
