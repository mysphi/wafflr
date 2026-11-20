package app.wafflr

import android.animation.ValueAnimator
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
 *  - The Wafflr logo always stays on screen at the edge, small and faded. Tap it to open or
 *    close the controls (it just turns solid; it never moves); drag it to move; long-press it
 *    to open the Wafflr app. Its size and gap from the edge are set in Settings.
 *  - Tucking: drag the logo into the edge of the screen and it tucks mostly out of sight (or,
 *    with the setting on, it tucks itself away after a few seconds unused). Tap the part still
 *    showing to bring it back.
 *  - When open, the speaker button slides out of the logo: upwards, or downwards when the logo
 *    is near the top of the screen. Tap it to read the whole screen (never the clipboard);
 *    while reading it becomes pause / resume.
 *  - Option buttons slide out beside the speaker, towards the middle of the screen:
 *    "draw a box" and "draw a shape" when idle, then previous / next / stop while reading.
 *
 * The logo and the controls are two separate overlay windows, so opening and closing the
 * controls never resizes (and so never shifts) the logo's window.
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
    private val logoSize = dp(prefs.bubbleSize.coerceIn(MIN_SIZE, MAX_SIZE)) // faded until opened
    private val gap = dp(10)
    private val pad = dp(6) // room for shadows around every button
    private val edgeGap = dp(prefs.bubbleEdge.coerceIn(0, MAX_EDGE)) // from the screen edge to the logo

    private val logo = RoundButton(R.drawable.ic_wafflr, "Wafflr: show or hide controls", iconSize = (logoSize * 0.63f).toInt(), diameter = logoSize)
    private val speaker = RoundButton(R.drawable.ic_speaker, "Read aloud").apply { setOnClickListener { onSpeakerTap() } }
    private val options = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val logoRoot = FrameLayout(context)
    private val controlsRoot = FrameLayout(context).apply { setPadding(pad, pad, pad, pad) }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )
    private val logoParams = overlayParams()
    private val controlsParams = overlayParams()

    private var shown = false
    private var hidden = false
    private var controlsAdded = false
    private var expanded = false
    private var below = false // controls open below the logo (when it's near the top)
    private var openedForReading = false
    private var state = ReaderState.IDLE
    private var optionsFor: Boolean? = null // which set of options is showing: true = playback
    private var tucked = false
    private var tuckAnim: ValueAnimator? = null
    private val autoTuck = Runnable { setTucked(true) }

    /** Options slide out towards the middle of the screen: to the left when docked on the right. */
    private val onRight get() = prefs.bubbleRight

    private val screen get() = wm.currentWindowMetrics.bounds
    private val statusBar get() = wm.currentWindowMetrics.windowInsets
        .getInsets(android.view.WindowInsets.Type.statusBars()).top

    /** Height of the logo's window. */
    private val logoWindowH get() = logoSize + 2 * pad

    fun show() {
        if (shown) return
        build()
        wm.addView(logoRoot, logoParams)
        shown = true
        reader.addListener(this)
        scheduleTuck()
    }

    fun hide() {
        if (!shown) return
        main.removeCallbacks(autoTuck)
        tuckAnim?.cancel()
        reader.removeListener(this)
        removeControls()
        try { wm.removeView(logoRoot) } catch (_: Throwable) {}
        shown = false
    }

    /** Hides the controls without removing them, e.g. while taking a screenshot. */
    fun setHidden(hidden: Boolean) {
        this.hidden = hidden
        val v = if (hidden) View.INVISIBLE else View.VISIBLE
        logoRoot.visibility = v
        controlsRoot.visibility = v
    }

    fun collapse() = setExpanded(false)

    private fun build() {
        // Logo window: just the logo, at a fixed spot on the edge.
        logoRoot.removeAllViews()
        val edge = edgeGap
        logoRoot.setPadding(if (onRight) pad else edge, pad, if (onRight) edge else pad, pad)
        logoRoot.addView(logo, FrameLayout.LayoutParams(logoSize, logoSize))
        logo.alpha = logoAlpha()
        logo.setOnTouchListener(DragHandler())
        logoParams.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
        logoParams.x = 0
        tucked = false
        logoParams.y = prefs.bubbleY.takeIf { it >= 0 }?.let { clampY(it) } ?: (screen.height() * 0.6).toInt()

        // Controls window: speaker plus options, beside the speaker towards the middle.
        controlsRoot.removeAllViews()
        row.removeAllViews()
        val optionsLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (onRight) {
            row.addView(options, optionsLp)
            row.addView(speaker, buttonLp())
        } else {
            row.addView(speaker, buttonLp())
            row.addView(options, optionsLp)
        }
        controlsRoot.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, size))
        controlsParams.gravity = logoParams.gravity
        // Line the speaker's centre up with the logo's.
        controlsParams.x = (edgeGap + logoSize / 2 - pad - size / 2).coerceAtLeast(0)
        optionsFor = null
        refreshOptions(animate = false)
    }

    // --- tucking into the edge -------------------------------------------------------------

    /** How far off the edge the logo goes when tucked: about two thirds of it. */
    private fun tuckedX() = -(edgeGap + (logoSize * 0.65f).toInt())

    private fun setTucked(on: Boolean) {
        if (on && (expanded || state != ReaderState.IDLE || !shown)) return
        main.removeCallbacks(autoTuck)
        if (on == tucked) return
        tucked = on
        tuckAnim?.cancel()
        tuckAnim = ValueAnimator.ofInt(logoParams.x, if (on) tuckedX() else 0).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                logoParams.x = it.animatedValue as Int
                try { if (shown) wm.updateViewLayout(logoRoot, logoParams) } catch (_: Throwable) {}
            }
            start()
        }
    }

    /** With the setting on, tuck away after a few seconds unused. */
    private fun scheduleTuck() {
        main.removeCallbacks(autoTuck)
        if (prefs.bubbleTuck && !expanded && state == ReaderState.IDLE) main.postDelayed(autoTuck, TUCK_DELAY_MS)
    }

    private fun clampY(y: Int) = y.coerceIn(statusBar, screen.height() - logoWindowH)

    private fun buttonLp() = LinearLayout.LayoutParams(size, size)

    // --- opening and closing ---------------------------------------------------------------

    /** Faded while closed and idle, solid while open or reading. */
    private fun logoAlpha() = if (expanded || state != ReaderState.IDLE) 1f else IDLE_ALPHA

    private fun fadeLogo() {
        logo.animate().alpha(logoAlpha()).setDuration(200).start()
    }

    /** Puts the controls window just above the logo, or just below it if there isn't room. */
    private fun placeControls() {
        val logoTop = logoParams.y + pad
        val aboveY = logoTop - gap - size - pad
        below = aboveY < statusBar
        controlsParams.y = if (below) logoTop + logoSize + gap - pad else aboveY
    }

    private fun addControls() {
        placeControls()
        controlsRoot.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (controlsAdded) {
            wm.updateViewLayout(controlsRoot, controlsParams)
        } else {
            wm.addView(controlsRoot, controlsParams)
            controlsAdded = true
        }
    }

    private fun removeControls() {
        if (!controlsAdded) return
        try { wm.removeView(controlsRoot) } catch (_: Throwable) {}
        controlsAdded = false
    }

    /** How far the speaker travels as it slides out of the logo. */
    private fun riseDistance() = (if (below) -1 else 1) * (size + gap).toFloat()

    private fun setExpanded(open: Boolean, animate: Boolean = true) {
        if (open == expanded) return
        expanded = open
        if (!open) openedForReading = false
        if (open) setTucked(false) else scheduleTuck()
        fadeLogo()
        speaker.animate().cancel()
        if (open) {
            addControls()
            // The speaker slides out of the logo...
            speaker.translationY = riseDistance()
            speaker.alpha = 0f
            speaker.animate().translationY(0f).alpha(1f).setDuration(220).setStartDelay(0)
                .setInterpolator(DecelerateInterpolator()).start()
            // ...then the options slide out from behind it.
            slideOptionsIn(delay = 120)
        } else if (!animate) {
            removeControls()
        } else {
            val dir = if (onRight) 1 else -1
            for (i in 0 until options.childCount) {
                val v = options.getChildAt(i)
                v.animate().translationX(dir * distanceToSpeaker(i)).alpha(0f).setStartDelay(0).setDuration(150).start()
            }
            speaker.animate().translationY(riseDistance()).alpha(0f).setDuration(180).setStartDelay(80)
                .withEndAction { if (!expanded) removeControls() }.start()
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
        fadeLogo()
        if (state != ReaderState.IDLE) setTucked(false) else if (was != ReaderState.IDLE) scheduleTuck()
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
    private inner class RoundButton(
        icon: Int,
        label: String,
        primary: Boolean = true,
        iconSize: Int = dp(24),
        diameter: Int = size,
    ) : FrameLayout(context) {
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
            addView(image, LayoutParams(iconSize, iconSize, Gravity.CENTER))
            addView(spinner, LayoutParams(diameter - dp(8), diameter - dp(8), Gravity.CENTER))
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
                    startY = logoParams.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        main.removeCallbacks(autoTuck)
                        tuckAnim?.cancel()
                        tucked = false
                        logoParams.x = 0
                    }
                    if (dragging) {
                        logoParams.y = clampY((startY + dy).toInt())
                        wm.updateViewLayout(logoRoot, logoParams)
                        if (controlsAdded && expanded) {
                            // The open controls follow the logo (flipping below it near the top).
                            val wasBelow = below
                            placeControls()
                            if (below != wasBelow) speaker.translationY = 0f
                            wm.updateViewLayout(controlsRoot, controlsParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        val screenW = wm.currentWindowMetrics.bounds.width()
                        val right = e.rawX > screenW / 2
                        prefs.bubbleY = logoParams.y
                        if (right != prefs.bubbleRight) {
                            val wasOpen = expanded
                            setExpanded(false, animate = false)
                            prefs.bubbleRight = right
                            build() // rebuild mirrored for the other side
                            wm.updateViewLayout(logoRoot, logoParams)
                            if (wasOpen) setExpanded(true)
                        }
                        // Let go right at the edge: tuck it away (tap to bring it back).
                        val atEdge = if (right) e.rawX > screenW - dp(EDGE_DROP_DP) else e.rawX < dp(EDGE_DROP_DP)
                        if (atEdge && !expanded) setTucked(true) else scheduleTuck()
                    } else if (!longPressed) {
                        v.performClick()
                        if (tucked) {
                            setTucked(false) // first tap just brings it back out
                            scheduleTuck()
                        } else {
                            setExpanded(!expanded)
                        }
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
        private const val IDLE_ALPHA = 0.45f
        const val MIN_SIZE = 28
        const val MAX_SIZE = 64
        const val MAX_EDGE = 48
        private const val TUCK_DELAY_MS = 4000L
        private const val EDGE_DROP_DP = 14
    }
}
