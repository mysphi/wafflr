package app.wafflr

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * How to use Wafflr: four short looping animations, one caption each, like a story.
 * Opens by itself on first launch, and from the cards on Home.
 */
class LearnActivity : WafflrScreen() {
    private class Step(val kind: DemoView.Kind, val bg: Int, val title: String, val caption: String)

    private val steps = listOf(
        Step(DemoView.Kind.STEP1, 0xFFEFE6D3.toInt(), "The waffle lives at the edge of every app",
            "Tap it to open the controls. Move it up, down or between the left and right screen edges. " +
                "Shift it into a screen edge to move it out of the way."),
        Step(DemoView.Kind.STEP2, 0xFFDCE7F7.toInt(), "Hear the whole page, and follow along",
            "Wafflr skips menus and adverts and reads the main text. Pause, skip back or skip ahead any time."),
        Step(DemoView.Kind.STEP3, 0xFFE4EEDF.toInt(), "Just want one part? Draw around it",
            "Pick the pen or the box, then draw a shape around the text. Works on photos and PDFs too."),
        Step(DemoView.Kind.STEP4, 0xFFF4DFD3.toInt(), "Selected some text? Wafflr is in the menu",
            "In Chrome and other apps that offer it, choose Read with Wafflr. Anywhere else, use Share."),
    )

    private var at = 0
    private lateinit var progress: Segments
    private lateinit var panel: FrameLayout
    private lateinit var stepLabel: TextView
    private lateinit var title: TextView
    private lateinit var caption: TextView
    private lateinit var back: View
    private lateinit var next: TextView
    private var demo: DemoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        at = (savedInstanceState?.getInt(EXTRA_STEP) ?: intent.getIntExtra(EXTRA_STEP, 0)).coerceIn(0, steps.size - 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paper.BG)
            setPadding(20.px, 10.px, 20.px, 24.px)
        }

        // Story-style progress, and Skip.
        progress = Segments()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(0, 4.px, 1f))
            addView(text("Skip", 14f, Paper.MUTED, 500).apply {
                gravity = Gravity.CENTER
                minHeight = 44.px
                setPadding(14.px, 0, 4.px, 0)
                contentDescription = "Skip the walkthrough"
                setOnClickListener { finish() }
            })
        })

        // The animation panel. Swipe it to move between steps.
        panel = FrameLayout(this).apply { clipToOutline = true }
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 300) return false
                if (vx < 0) go(at + 1) else go(at - 1)
                return true
            }
        })
        panel.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 8.px })

        stepLabel = text("", 13f, Paper.BLUE, 600)
        title = text("", 30f, weight = 500, serif = true, lineSpacing = 1.05f).apply { letterSpacing = -0.02f }
        caption = note("", 15f)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18.px, 0, 18.px)
            addView(stepLabel)
            addView(title.apply { setPadding(0, 6.px, 0, 0) })
            addView(caption.apply { setPadding(0, 10.px, 0, 0) })
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })

        back = iconButton(R.drawable.ic_back, Paper.INK, null, "Back", size = 52, iconSize = 20) { go(at - 1) }.apply {
            background = pressable(shape(0x00000000, 999f, 1.px, Paper.TRACK))
        }
        next = pillButton("Next", Look.DARK, size = 16f) { if (at == steps.size - 1) finish() else go(at + 1) }.apply { minHeight = 52.px }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back, LinearLayout.LayoutParams(52.px, 52.px).apply { marginEnd = 10.px })
            addView(next, LinearLayout.LayoutParams(0, 52.px, 1f))
        })

        setContentView(root)
        show(animate = false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(EXTRA_STEP, at)
    }

    private fun go(i: Int) {
        if (i !in steps.indices || i == at) return
        at = i
        show(animate = true)
    }

    private fun show(animate: Boolean) {
        val s = steps[at]
        panel.background = shape(s.bg, 28f)
        panel.removeAllViews()
        val view = DemoView(this, s.kind).apply { onProgress = { progress.fill = it } }
        panel.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        demo = view
        progress.current = at
        progress.fill = 0f
        stepLabel.text = "Step ${at + 1} of ${steps.size}"
        title.text = s.title
        caption.text = s.caption
        back.visibility = if (at == 0) View.GONE else View.VISIBLE
        if (at == steps.size - 1) {
            next.text = "Start reading"
            next.background = pressable(shape(Paper.BLUE, 999f), true)
            next.setTextColor(0xFFFFFFFF.toInt())
        } else {
            next.text = "Next"
            next.background = pressable(shape(Paper.INK, 999f), true)
            next.setTextColor(Paper.CARD)
        }
        if (animate) {
            for (v in listOf(view, title, caption)) {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    /** Four thin bars along the top: done, filling (this step) and to come. */
    private inner class Segments : View(this@LearnActivity) {
        var current = 0
            set(v) { field = v; invalidate() }
        var fill = 0f
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(c: Canvas) {
            val n = steps.size
            val gap = 6 * dp
            val w = (width - gap * (n - 1)) / n
            val r = height / 2f
            for (i in 0 until n) {
                val x = i * (w + gap)
                box.set(x, 0f, x + w, height.toFloat())
                paint.color = Paper.TRACK
                c.drawRoundRect(box, r, r, paint)
                val f = when { i < current -> 1f; i == current -> fill; else -> 0f }
                if (f > 0) {
                    paint.color = Paper.INK
                    box.set(x, 0f, x + w * f, height.toFloat())
                    c.drawRoundRect(box, r, r, paint)
                }
            }
        }
    }

    companion object {
        const val EXTRA_STEP = "step"
    }
}
