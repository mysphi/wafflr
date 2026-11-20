package app.wafflr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.CompoundButton

/**
 * The "soft paper" look for Wafflr's own screens: warm cream paper, a serif for headlines
 * (Fraunces), Inter for everything else, and Wafflr blue as the one accent.
 * Both fonts are open source (SIL Open Font License) and ship in assets/fonts.
 */
object Paper {
    const val BG = 0xFFFBF7EE.toInt()
    const val INK = 0xFF1E1B16.toInt()
    const val MUTED = 0xFF5B5446.toInt()
    const val SOFT = 0xFF6A6252.toInt()
    const val CARD = 0xFFFFFDF8.toInt()
    const val LINE = 0xFFEBE3D2.toInt()
    const val CHIP = 0xFFE7DFCF.toInt()
    const val TRACK = 0xFFE2D8C4.toInt()
    const val BLUE = 0xFF2D6CDF.toInt()
    const val BLUE_DARK = 0xFF1F4FA8.toInt()
    const val BLUE_PALE = 0xFFE3ECFB.toInt()
    const val BLUE_ROW = 0xFFEEF3FC.toInt()
    const val BLUE_PILL = 0xFFD7E4FA.toInt()
    const val AMBER = 0xFFF2B437.toInt()
    const val AMBER_FILL = 0x38FFD54F
    const val UNDERLINE = 0xFFF29A00.toInt()
    const val ON_BG = 0xFFE8F3EA.toInt()
    const val ON_INK = 0xFF1F5A2C.toInt()
    const val ON_DOT = 0xFF2E8B47.toInt()
    const val OFF_BG = 0xFFFBE9CF.toInt()
    const val OFF_INK = 0xFF7A4A0B.toInt()
    const val OFF_DOT = 0xFFD98A0B.toInt()
    const val DARK_SUB = 0xFFCFC6B4.toInt()

    /** Avatar colours (background, letter) for each voice, in catalogue order. */
    val avatars = listOf(
        BLUE_PALE to BLUE,
        0xFFF1E3C8.toInt() to 0xFF6B4E16.toInt(),
        0xFFE2EDE1.toInt() to 0xFF2F5B2C.toInt(),
        0xFFF4DFD3.toInt() to 0xFF8A3B1C.toInt(),
        0xFFEEE6FB.toInt() to 0xFF5A3AA8.toInt(),
    )

    private val fonts = HashMap<String, Typeface>()

    /** Fraunces at the given weight, for headlines. */
    fun serif(ctx: Context, weight: Int = 500): Typeface =
        font(ctx, "fonts/Fraunces.ttf", "'opsz' 48, 'wght' $weight", Typeface.SERIF)

    /** Inter at the given weight, for body text and buttons. */
    fun sans(ctx: Context, weight: Int = 400): Typeface =
        font(ctx, "fonts/Inter.ttf", "'opsz' 14, 'wght' $weight", Typeface.DEFAULT)

    private fun font(ctx: Context, path: String, axes: String, fallback: Typeface): Typeface =
        synchronized(fonts) {
            fonts.getOrPut("$path|$axes") {
                try {
                    Typeface.Builder(ctx.assets, path).setFontVariationSettings(axes).build() ?: fallback
                } catch (_: Throwable) {
                    fallback
                }
            }
        }

    /** Voice name and accent from a catalogue label: "Alan (Southern English Male)" → Alan, "Southern English, male". */
    fun name(v: Voice): String = v.label.substringBefore(" (").trim()

    fun accent(v: Voice): String {
        val inner = v.label.substringAfter("(", "").substringBefore(")").trim()
        val words = inner.split(' ')
        if (words.size < 2) return inner
        return words.dropLast(1).joinToString(" ") + ", " + words.last().lowercase()
    }

    fun avatarFor(v: Voice): Pair<Int, Int> =
        avatars[VoiceCatalog.voices.indexOf(v).coerceAtLeast(0) % avatars.size]
}

/** A round on/off switch in the paper style. Behaves as a normal Android switch for TalkBack. */
class PaperSwitch(ctx: Context) : CompoundButton(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pos = 0f
    private val box = RectF()

    init {
        buttonDrawable = null
        background = null
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
    }

    override fun setChecked(checked: Boolean) {
        val was = isChecked
        super.setChecked(checked)
        if (!isLaidOut || was == checked) { pos = if (checked) 1f else 0f; invalidate(); return }
        animate().cancel()
        android.animation.ValueAnimator.ofFloat(pos, if (checked) 1f else 0f).apply {
            duration = 160
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((44 * d).toInt(), (44 * d).toInt())

    override fun onDraw(c: Canvas) {
        val tw = 44 * d
        val th = 26 * d
        val top = (height - th) / 2
        box.set(0f, top, tw, top + th)
        paint.color = blend(0xFFD8CEBA.toInt(), Paper.BLUE, pos)
        if (!isEnabled) paint.alpha = 110
        c.drawRoundRect(box, th / 2, th / 2, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val r = 10 * d
        val cx = 3 * d + r + pos * (tw - 6 * d - 2 * r)
        c.drawCircle(cx, top + th / 2, r, paint)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
