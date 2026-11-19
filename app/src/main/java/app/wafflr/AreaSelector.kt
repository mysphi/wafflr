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
import android.widget.ImageView
import android.widget.LinearLayout
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
    // Just two round buttons at the top, styled like the floating controls: the current mode
    // (box or draw; tap it to switch) and cancel.
    private val modeButton = roundButton(R.drawable.ic_box, primary = true) { toggleMode() }
    private val cancelButton = roundButton(R.drawable.ic_close, primary = false) { finish(null) }.apply {
        contentDescription = "Cancel"
    }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6)) // room for shadows
        addView(modeButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        addView(cancelButton, LinearLayout.LayoutParams(dp(52), dp(52)))
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
        (modeButton.getChildAt(0) as ImageView).setImageResource(if (freehand) R.drawable.ic_draw else R.drawable.ic_box)
        modeButton.contentDescription =
            if (freehand) "Drawing around text. Tap to drag a box instead" else "Dragging a box over text. Tap to draw instead"
    }

    private fun roundButton(icon: Int, primary: Boolean, action: () -> Unit) = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (primary) "#2D6CDF" else "#E6EEFC"))
        }
        elevation = dp(3).toFloat()
        isClickable = true
        addView(ImageView(context).apply {
            setImageResource(icon)
            if (!primary) setColorFilter(Color.parseColor("#2D6CDF"))
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
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
