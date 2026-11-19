package app.wafflr

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.abs

/**
 * Wafflr's floating controls, drawn over every app.
 *
 *  - The Wafflr logo always stays on screen at the edge. Tap it to open or close the controls;
 *    drag it to move them; long-press it to open the Wafflr app.
 *  - When open, the speaker button slides up out of the logo. Tap it to read (copied text if you
 *    just copied some, otherwise the whole screen); while reading it becomes pause / resume.
 *  - Option buttons slide out beside the speaker, towards the middle of the screen:
 *    "draw a box" and "draw a shape" when idle, then previous / next / stop while reading.
 *
 * Every button is the same size, colour and icon style.
 */
class FloatingBubble(
    private val context: Context,
    private val prefs: Prefs,
    private val reader: Reader,
    private val onRead: () -> Unit,
    private val onSelectArea: (freehand: Boolean) -> Unit,
    private val onOpenApp: () -> Unit,
) : ReaderListener {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)!!
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val size = dp(52)
    private val gap = dp(10)

    private val logo = RoundButton(R.drawable.ic_wafflr, "Wafflr: show or hide controls")
    private val speaker = RoundButton(R.drawable.ic_speaker, "Read aloud").apply { setOnClickListener { onSpeakerTap() } }
    private val options = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6)) // room for shadows
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private var shown = false
    private var expanded = false
    private var openedForReading = false
    private var state = ReaderState.IDLE
    private var optionsFor: Boolean? = null // which set of options is showing: true = playback

    /** Options slide out towards the middle of the screen: to the left when docked on the right. */
    private val onRight get() = prefs.bubbleRight

    fun show() {
        if (shown) return
        build()
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

    /** Hides the controls without removing them, e.g. while taking a screenshot. */
    fun setHidden(hidden: Boolean) {
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun collapse() = setExpanded(false)

    private fun build() {
        root.removeAllViews()
        topRow.removeAllViews()
        root.gravity = if (onRight) Gravity.END else Gravity.START

        val optionsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (onRight) {
            topRow.addView(options, optionsLp)
            topRow.addView(speaker, buttonLp())
        } else {
            topRow.addView(speaker, buttonLp())
            topRow.addView(options, optionsLp)
        }
        root.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = gap
        })
        root.addView(logo, buttonLp())
        topRow.visibility = if (expanded) View.VISIBLE else View.GONE
        optionsFor = null
        refreshOptions(animate = false)
        logo.setOnTouchListener(DragHandler())

        // Anchored to the bottom so the logo stays put while the controls open above it.
        params.gravity = Gravity.BOTTOM or if (onRight) Gravity.END else Gravity.START
        params.x = 0
        val screenH = wm.currentWindowMetrics.bounds.height()
        params.y = prefs.bubbleY.takeIf { it >= 0 }?.coerceIn(0, screenH - size * 2) ?: (screenH * 0.3).toInt()
    }

    private fun buttonLp() = LinearLayout.LayoutParams(size, size)

    // --- opening and closing ---------------------------------------------------------------

    private fun setExpanded(open: Boolean) {
        if (open == expanded) return
        expanded = open
        if (!open) openedForReading = false
        speaker.animate().cancel()
        if (open) {
            topRow.visibility = View.VISIBLE
            // The speaker rises out of the logo...
            speaker.translationY = (size + gap).toFloat()
            speaker.alpha = 0f
            speaker.animate().translationY(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            // ...then the options slide out from behind it.
            slideOptionsIn(delay = 120)
        } else {
            val dir = if (onRight) 1 else -1
            for (i in 0 until options.childCount) {
                val v = options.getChildAt(i)
                v.animate().translationX(dir * distanceToSpeaker(i)).alpha(0f).setDuration(150).start()
            }
            speaker.animate().translationY((size + gap).toFloat()).alpha(0f).setDuration(180).setStartDelay(80)
                .withEndAction { if (!expanded) topRow.visibility = View.GONE }.start()
        }
    }

    /** Distance from option [i] back to the speaker, so it can slide out from behind it. */
    private fun distanceToSpeaker(i: Int): Float {
        val n = options.childCount
        val steps = if (onRight) n - i else i + 1
        return (steps * (size + gap)).toFloat()
    }

    private fun slideOptionsIn(delay: Long) {
        val dir = if (onRight) 1 else -1
        for (i in 0 until options.childCount) {
            val v = options.getChildAt(i)
            v.animate().cancel()
            v.translationX = dir * distanceToSpeaker(i)
            v.alpha = 0f
            val order = if (onRight) options.childCount - 1 - i else i // nearest the speaker first
            v.animate().translationX(0f).alpha(1f).setDuration(200).setStartDelay(delay + order * 40L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // --- buttons ---------------------------------------------------------------------------

    /** Idle: box and freehand. Reading: previous, next, stop. Same left-to-right order on both sides. */
    private fun refreshOptions(animate: Boolean) {
        val playback = state != ReaderState.IDLE
        if (optionsFor == playback) return
        optionsFor = playback
        options.removeAllViews()
        val buttons = if (playback) listOf(
            RoundButton(R.drawable.ic_previous, "Previous sentence", primary = false).apply { setOnClickListener { reader.previous() } },
            RoundButton(R.drawable.ic_next, "Next sentence", primary = false).apply { setOnClickListener { reader.next() } },
            RoundButton(R.drawable.ic_stop, "Stop", primary = false).apply { setOnClickListener { reader.stop() } },
        ) else listOf(
            RoundButton(R.drawable.ic_box, "Drag a box around text to read", primary = false).apply { setOnClickListener { pickArea(false) } },
            RoundButton(R.drawable.ic_draw, "Draw around text to read", primary = false).apply { setOnClickListener { pickArea(true) } },
        )
        for (b in buttons) {
            options.addView(b, buttonLp().apply {
                if (onRight) rightMargin = gap else leftMargin = gap
            })
        }
        if (animate && expanded) slideOptionsIn(delay = 0)
    }

    private fun pickArea(freehand: Boolean) {
        setExpanded(false)
        onSelectArea(freehand)
    }

    private fun onSpeakerTap() {
        when (state) {
            ReaderState.IDLE -> onRead()
            ReaderState.PREPARING -> {}
            else -> reader.togglePause()
        }
    }

    override fun onStatus(status: ReaderStatus) {
        val was = state
        state = status.state
        speaker.setIcon(
            when (state) {
                ReaderState.PLAYING -> R.drawable.ic_pause
                ReaderState.PAUSED -> R.drawable.ic_play
                else -> R.drawable.ic_speaker
            },
            when (state) {
                ReaderState.PLAYING -> "Pause reading"
                ReaderState.PAUSED -> "Resume reading"
                ReaderState.PREPARING -> "Wafflr is getting ready"
                ReaderState.IDLE -> "Read aloud"
            },
        )
        speaker.setBusy(state == ReaderState.PREPARING)
        refreshOptions(animate = true)
        if (state != ReaderState.IDLE && !expanded) {
            // Reading started from elsewhere (e.g. Share): show the player.
            openedForReading = true
            setExpanded(true)
        } else if (state == ReaderState.IDLE && was != ReaderState.IDLE && openedForReading) {
            setExpanded(false)
        }
    }

    override fun onError(message: String) {
        android.widget.Toast.makeText(context, "Wafflr: ${message.lineSequence().first()}", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * A round button: same size, shape and icon style everywhere. The logo and speaker are solid
     * blue; the options beside them are pale blue with blue icons, so the main action stands out.
     * Flat, no outline, a soft shadow.
     */
    private inner class RoundButton(icon: Int, label: String, primary: Boolean = true) : FrameLayout(context) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val spinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.WHITE else Color.parseColor(BLUE))
            visibility = View.GONE
        }

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (primary) BLUE else PALE))
            }
            if (!primary) image.setColorFilter(Color.parseColor(BLUE))
            elevation = dp(3).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            isClickable = true
            val iconSize = dp(24)
            addView(image, LayoutParams(iconSize, iconSize, Gravity.CENTER))
            addView(spinner, LayoutParams(size - dp(8), size - dp(8), Gravity.CENTER))
            setIcon(icon, label)
        }

        fun setIcon(icon: Int, label: String) {
            image.setImageResource(icon)
            contentDescription = label
        }

        fun setBusy(busy: Boolean) {
            spinner.visibility = if (busy) View.VISIBLE else View.GONE
            image.alpha = if (busy) 0.5f else 1f
        }
    }

    /** Logo: tap toggles, drag moves, long-press opens the app. */
    private inner class DragHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                logo.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                setExpanded(false)
                onOpenApp()
            }
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
                        // Anchored to the bottom: moving the finger down lowers y.
                        params.y = (startY - dy).toInt().coerceAtLeast(0)
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
                            build() // rebuild mirrored for the other side
                            wm.updateViewLayout(root, params)
                        }
                    } else if (!longPressed) {
                        v.performClick()
                        setExpanded(!expanded)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    companion object {
        private const val BLUE = "#2D6CDF"
        private const val PALE = "#E6EEFC"
    }
}
