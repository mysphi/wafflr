package app.wafflr

import android.app.Activity
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Shared look and helpers for Wafflr's own screens, in the soft paper style (see [Paper]). */
abstract class WafflrScreen : Activity() {
    protected val app get() = WafflrApp.instance
    protected val prefs get() = app.prefs
    protected val dp get() = resources.displayMetrics.density
    protected val Int.px get() = (this * dp).toInt()

    protected fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WafflrService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    // --- building blocks ---------------------------------------------------------------------

    protected fun shape(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * dp
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    protected fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** [bg] with a touch ripple, clipped to the same shape. */
    protected fun pressable(bg: Drawable, dark: Boolean = false): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x1F1E1B16), bg, bg)

    protected fun text(
        t: CharSequence, size: Float = 15f, color: Int = Paper.INK, weight: Int = 400,
        serif: Boolean = false, lineSpacing: Float = 1.0f,
    ) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (serif) Paper.serif(this@WafflrScreen, weight) else Paper.sans(this@WafflrScreen, weight)
        setLineSpacing(0f, lineSpacing)
        includeFontPadding = !serif
        if (serif) letterSpacing = -0.015f
    }

    /** Muted explanatory text. */
    protected fun note(t: CharSequence, size: Float = 14f) = text(t, size, Paper.MUTED, lineSpacing = 1.3f)

    /** Small uppercase label above a group, e.g. "ON YOUR PHONE". */
    protected fun sectionLabel(t: String) = text(t.uppercase(), 12f, Paper.SOFT, 600).apply {
        letterSpacing = 0.06f
        setPadding(20.px, 22.px, 20.px, 8.px)
    }

    /** White card with a hairline border; a vertical stack unless told otherwise. */
    protected fun card(padding: Int = 16, horizontal: Boolean = false, radius: Float = 20f) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        background = shape(Paper.CARD, radius, 1.px, Paper.LINE)
        setPadding(padding.px, padding.px, padding.px, padding.px)
        clipToOutline = true
    }

    /** Layout params with the page's 20dp side margins. */
    protected fun margins(top: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { setMargins(20.px, top.px, 20.px, 0) }

    enum class Look { DARK, LIGHT, BLUE, OUTLINE, CREAM }

    /** A rounded button, at least 44dp tall. */
    protected fun pillButton(label: String, look: Look = Look.OUTLINE, radius: Float = 999f, size: Float = 14f, onClick: () -> Unit) =
        text(label, size, weight = if (look == Look.BLUE || look == Look.DARK) 600 else 500).apply {
            val (bg, fg, stroke) = when (look) {
                Look.DARK -> Triple(Paper.INK, Paper.CARD, 0)
                Look.LIGHT -> Triple(Paper.BG, Paper.INK, 0)
                Look.BLUE -> Triple(Paper.BLUE, 0xFFFFFFFF.toInt(), 0)
                Look.OUTLINE -> Triple(Paper.CARD, Paper.INK, Paper.CHIP)
                Look.CREAM -> Triple(Paper.BG, Paper.INK, Paper.CHIP)
            }
            setTextColor(fg)
            background = pressable(shape(bg, radius, if (stroke != 0) 1.px else 0, stroke), look == Look.DARK || look == Look.BLUE)
            gravity = Gravity.CENTER
            minHeight = 44.px
            setPadding(16.px, 0, 16.px, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A round icon button. */
    protected fun iconButton(icon: Int, tint: Int, bg: Int?, description: String, size: Int = 44, iconSize: Int = 20, onClick: () -> Unit) =
        FrameLayout(this).apply {
            contentDescription = description
            background = pressable(if (bg != null) oval(bg) else oval(0x00000000), bg == Paper.BLUE || bg == Paper.INK)
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@WafflrScreen).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            }, FrameLayout.LayoutParams(iconSize.px, iconSize.px, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(size.px, size.px)
        }

    /** The page header used by inner pages: a back arrow and a serif title. */
    protected fun backHeader(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.px, 16.px, 12.px, 4.px)
        addView(iconButton(R.drawable.ic_back, Paper.INK, null, "Back", iconSize = 22) { finish() })
        addView(text(title, 26f, weight = 500, serif = true).apply { setPadding(8.px, 0, 0, 0) })
    }

    /** A round letter avatar for a voice. */
    protected fun avatar(v: Voice, size: Int = 44, filled: Boolean = false) = text(Paper.name(v).take(1), size * 0.45f, weight = 600, serif = true).apply {
        val (bg, fg) = Paper.avatarFor(v)
        background = oval(if (filled) Paper.BLUE else bg)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else fg)
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(size.px, size.px)
    }

    /** A title with a muted line under it, filling the space it is given. */
    protected fun twoLines(title: String, detail: String?, titleSize: Float = 15f, titleWeight: Int = 600) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, titleSize, weight = titleWeight))
        if (!detail.isNullOrEmpty()) addView(text(detail, 13f, Paper.MUTED, lineSpacing = 1.25f).apply { setPadding(0, 2.px, 0, 0) })
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** A whole-row on/off setting: title, optional detail and a switch. Tapping anywhere toggles it. */
    protected fun switchRow(title: String, detail: String?, on: Boolean, set: (Boolean) -> Unit): Pair<LinearLayout, PaperSwitch> {
        val sw = PaperSwitch(this).apply {
            isChecked = on
            contentDescription = title
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.px, 10.px, 12.px, 10.px)
            minimumHeight = 56.px
            background = pressable(shape(0x00000000, 0f))
            setOnClickListener { if (sw.isEnabled) sw.toggle() }
            addView(twoLines(title, detail, titleWeight = 500))
            addView(sw)
        }
        return row to sw
    }

    protected fun divider(inset: Int = 16) = View(this).apply {
        setBackgroundColor(Paper.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.px).apply { setMargins(inset.px, 0, inset.px, 0) }
    }

    /** A pill that toggles on and off. */
    protected fun chip(label: String, on: Boolean, set: (Boolean) -> Unit): TextView = text(label, 14f, weight = 500).apply {
        gravity = Gravity.CENTER
        minHeight = 44.px
        setPadding(14.px, 0, 14.px, 0)
        isClickable = true
        var state = on
        fun paint() {
            background = pressable(shape(if (state) Paper.BLUE_PALE else Paper.CARD, 999f, 1.px, if (state) Paper.BLUE_DARK else Paper.CHIP))
            setTextColor(if (state) Paper.BLUE_DARK else Paper.INK)
            contentDescription = "$label, ${if (state) "on" else "off"}"
        }
        paint()
        setOnClickListener { state = !state; paint(); set(state) }
    }

    /** A paper-styled slider. */
    protected fun seekBar(steps: Int, at: Int, onChange: (Int, Boolean) -> Unit, onRelease: (Int) -> Unit = {}) = SeekBar(this).apply {
        max = steps
        progress = at.coerceIn(0, steps)
        progressTintList = ColorStateList.valueOf(Paper.BLUE)
        progressBackgroundTintList = ColorStateList.valueOf(Paper.CHIP)
        thumbTintList = ColorStateList.valueOf(Paper.BLUE)
        setPadding(10.px, 12.px, 10.px, 12.px)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) = onRelease(progress)
        })
    }

    /** A label and value on one line above a slider. Returns the block and the value text. */
    protected fun labelledSlider(label: String, slider: SeekBar): Pair<LinearLayout, TextView> {
        val value = text("", 13f, Paper.MUTED)
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@WafflrScreen).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(label, 13f, weight = 500), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(-10.px, 0, -10.px, 0)
            })
        }
        return block to value
    }

    protected fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Paper.BG)
        setPadding(0, 0, 0, 32.px)
    }

    protected fun scroll(content: View) = android.widget.ScrollView(this).apply {
        setBackgroundColor(Paper.BG)
        isFillViewport = true
        addView(content)
    }

    protected fun mono(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Paper.INK)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, 4.px, 0, 4.px)
    }
}
