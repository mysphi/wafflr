package app.wafflr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Short looping animations that show how Wafflr works: the three small cards on Home and the
 * four walkthrough steps. All drawn on a canvas, so there are no video files to ship.
 * If the phone has animations turned off, each shows one still frame instead.
 */
class DemoView(ctx: Context, val kind: Kind) : View(ctx) {
    enum class Kind(val ms: Long, val description: String) {
        CARD_READ(3200, "A highlight moving down a page of text"),
        CARD_DRAW(3000, "A pen line drawn around a paragraph"),
        CARD_SELECT(3000, "A selection menu with Read with Wafflr"),
        STEP1(8000, "Animation: the waffle is tucked into the screen edge. A tap brings it out, another tap opens the speaker, box and pen buttons above it, then it tucks away again."),
        STEP2(7000, "Animation: a yellow box surrounds the paragraph being read and a line under each word follows the voice. When the paragraph ends the box goes, then appears on the next one."),
        STEP3(5000, "Animation: a line is drawn loosely around a paragraph, then the whole paragraph is highlighted."),
        STEP4(6000, "Animation: text is selected, Read with Wafflr is chosen from the menu, and the text is highlighted."),
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val seg = Path()
    private var t = 0f
    private var elapsed = 0L
    private var animator: ValueAnimator? = null

    /** Called every frame with how far through its loop the animation is (0 to 1). */
    var onProgress: ((Float) -> Unit)? = null

    private fun icon(res: Int, tint: Int): Drawable = context.getDrawable(res)!!.mutate().apply { setTint(tint) }
    private val icons = HashMap<Pair<Int, Int>, Drawable>()
    private fun cachedIcon(res: Int, tint: Int) = icons.getOrPut(res to tint) { icon(res, tint) }

    init {
        contentDescription = kind.description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /** Starts the loop from the beginning (or shows a still frame if animations are off). */
    fun restart() {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            t = STILL[kind] ?: 0.5f
            invalidate()
            onProgress?.invoke(1f)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = kind.ms
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                elapsed = it.currentPlayTime
                onProgress?.invoke(t)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        when (kind) {
            Kind.CARD_READ, Kind.CARD_DRAW, Kind.CARD_SELECT -> {
                c.save(); c.scale(d, d); drawCard(c); c.restore()
            }
            else -> drawStep(c)
        }
    }

    // --- Home cards (150 x 168 dp) -----------------------------------------------------------

    private fun drawCard(c: Canvas) {
        val w = width / d
        when (kind) {
            Kind.CARD_READ -> {
                bars(c, 16f, 22f, w - 32, 6f, 7f, 0xFFCDBFA4.toInt(), 0.9f, 1f, 0.7f, -10f, 0.95f, 0.8f, 0.6f)
                // One paragraph at a time: the box goes, then appears on the next.
                if (t < .47f) { r.set(10f, 16f, w - 10, 60f); highlight(c, r, 8f, kf(t, 0f, 0f, .04f, 1f, .43f, 1f, .47f, 0f)) }
                else if (t > .53f) { r.set(10f, 65f, w - 10, 109f); highlight(c, r, 8f, kf(t, .53f, 0f, .57f, 1f, .96f, 1f, 1f, 0f)) }
                playBadge(c, w)
            }
            Kind.CARD_DRAW -> {
                bars(c, 16f, 40f, w - 32, 6f, 7f, 0xFFA9BEDD.toInt(), 1f, 0.85f, 0.92f, 0.6f)
                val path = Path().apply {
                    moveTo(20f, 34f); cubicTo(60f, 20f, 120f, 22f, 134f, 40f)
                    cubicTo(148f, 58f, 128f, 86f, 92f, 90f); cubicTo(56f, 94f, 14f, 88f, 12f, 62f)
                    cubicTo(10f, 36f, 16f, 38f, 30f, 32f)
                }
                pen(c, path, kf(t, 0f, 0f, .6f, 1f), 2.5f, Paper.BLUE, 255)
                playBadge(c, w)
            }
            Kind.CARD_SELECT -> {
                var y = 60f
                for ((frac, col) in listOf(1f to 0xFFDDB8A3.toInt(), 0.75f to 0xFF9DBBEA.toInt(), 0.88f to 0xFFDDB8A3.toInt())) {
                    p.color = col; r.set(16f, y, 16f + (w - 32) * frac, y + 6); c.drawRoundRect(r, 3f, 3f, p); y += 13f
                }
                val a = kf(t, 0f, 0f, .3f, 0f, .45f, 1f, .9f, 1f, 1f, 0f)
                val dy = kf(t, 0f, 6f, .3f, 6f, .45f, 0f)
                menu(c, 16f, 22f + dy, listOf("Copy", "Read with Wafflr"), -1, a, 9f, separator = true)
            }
            else -> {}
        }
    }

    private fun playBadge(c: Canvas, w: Float) {
        val h = height / d
        p.color = Paper.BLUE
        c.drawCircle(w - 24, h - 24, 14f, p)
        val ic = cachedIcon(R.drawable.ic_play, 0xFFFFFFFF.toInt())
        ic.setBounds((w - 31).toInt(), (h - 31).toInt(), (w - 17).toInt(), (h - 17).toInt()); ic.draw(c)
    }

    // --- Walkthrough steps: a mini phone (230 x 440) in a coloured panel ------------------------

    private fun drawStep(c: Canvas) {
        val s = min(min((width - 24 * d) / (230 * d), (height - 24 * d) / (440 * d)), 1.25f) * d
        c.save()
        c.translate((width - 230 * s) / 2, (height - 440 * s) / 2)
        c.scale(s, s)
        p.color = Paper.INK; r.set(0f, 0f, 230f, 440f); c.drawRoundRect(r, 30f, 30f, p)
        p.color = 0xFFFFFFFF.toInt(); r.set(6f, 6f, 224f, 434f); c.drawRoundRect(r, 24f, 24f, p)
        c.save()
        c.clipRect(6f, 6f, 224f, 434f)
        c.translate(6f, 6f)
        when (kind) {
            Kind.STEP1 -> step1(c)
            Kind.STEP2 -> step2(c)
            Kind.STEP3 -> step3(c)
            Kind.STEP4 -> step4(c)
            else -> {}
        }
        c.restore()
        c.restore()
        // Callouts sit on the panel, outside the phone.
        when (kind) {
            Kind.STEP1 -> callout(c, when {
                t < .26f -> "Tucked into the edge"
                t < .76f -> "Tap the waffle"
                else -> "It tucks away again"
            }, 22 * d, 22 * d, dark = true)
            Kind.STEP2 -> {
                callout(c, "Tap the speaker", 22 * d, 22 * d, dark = true)
                callout(c, "The box and underline follow the voice", width - 18 * d - 134 * d, height * 0.6f, dark = false, maxW = 110 * d)
            }
            Kind.STEP3 -> callout(c, "Loose is fine: whole paragraphs are read", 22 * d, -22 * d, dark = true)
            Kind.STEP4 -> callout(c, "Select, then Read with Wafflr", 22 * d, 22 * d, dark = true)
            else -> {}
        }
    }

    /** One block of the pretend page: a line of text ([h] tall, [frac] of the width) or a gap. */
    private class Block(val h: Float, val frac: Float, val color: Int = 0, val radius: Float = 3f)

    private fun gap(h: Float) = Block(h, 0f)
    private fun title() = Block(12f, 0.7f, 0xFFD9D2C4.toInt(), 4f)
    private fun lines(color: Int, vararg f: Float) = f.map { Block(7f, it, color) }

    /** Draws the blocks from [top] down and returns where each one landed. */
    private fun page(c: Canvas, blocks: List<Block>, top: Float = 34f): List<RectF> {
        var y = top
        val out = ArrayList<RectF>()
        for ((i, b) in blocks.withIndex()) {
            if (i > 0) y += 8f
            val rect = RectF(18f, y, 18f + 182f * b.frac, y + b.h)
            if (b.frac > 0) { p.color = b.color; c.drawRoundRect(rect, b.radius, b.radius, p) }
            out += rect
            y += b.h
        }
        return out
    }

    private fun around(rects: List<RectF>, pad: Float = 10f) =
        RectF(12f, rects.first().top - pad, 206f, rects.last().bottom + pad)

    private fun step1(c: Canvas) {
        val col = 0xFFE6E0D4.toInt()
        page(c, listOf(title(), gap(6f)) + lines(col, 1f, .92f, .96f, .6f) + gap(8f) + lines(col, .98f, .88f, .94f, .4f))
        // The waffle starts tucked into the right edge, slides out when tapped, and tucks away at the end.
        val lx = 191f
        val ly = 300f
        val out = kf(t, 0f, 0f, .14f, 0f, .2f, 1f, .82f, 1f, .9f, 0f)
        val cx = lerp(lx + 32f, lx, out)
        // Controls: the speaker rises out of the waffle, then the box and pen slide out towards the middle.
        val open = kf(t, 0f, 0f, .3f, 0f, .38f, 1f, .7f, 1f, .76f, 0f)
        if (open > 0) {
            val sy = lerp(ly, ly - 17f - 10f - 15f, open)
            val items = listOf(R.drawable.ic_box, R.drawable.ic_draw)
            for ((i, icon) in items.withIndex()) {
                val dx = (2 - i) * 36f * kf(open, 0f, 0f, .5f, 0f, 1f, 1f)
                circleIcon(c, lx - dx, sy, 15f, icon, 0xFFE6EEFC.toInt(), Paper.BLUE, 14f, kf(open, 0f, 0f, .5f, 0f, 1f, 1f))
            }
            circleIcon(c, lx, sy, 15f, R.drawable.ic_speaker, Paper.BLUE, 0xFFFFFFFF.toInt(), 14f, open)
        }
        // Faded while idle, solid while open.
        val la = kf(t, 0f, .5f, .26f, .5f, .3f, 1f, .74f, 1f, .78f, .5f)
        circleIcon(c, cx, ly, 17f, R.drawable.ic_wafflr, Paper.BLUE, 0xFFFFFFFF.toInt(), 16f, la)
        // Finger taps: on the tucked edge, then on the waffle to open, then to close.
        tap(c, 210f, ly, .06f, .16f)
        tap(c, lx, ly, .24f, .34f)
        tap(c, lx, ly, .64f, .74f)
    }

    /** A finger tap: a ring that grows and fades between [from] and [to]. */
    private fun tap(c: Canvas, x: Float, y: Float, from: Float, to: Float) {
        if (t < from || t > to) return
        val f = (t - from) / (to - from)
        val a = if (f < 0.3f) f / 0.3f * 0.8f else 0.8f * (1 - (f - 0.3f) / 0.7f)
        stroke.color = Paper.INK; stroke.strokeWidth = 3f; stroke.alpha = (a * 255).toInt()
        c.drawCircle(x, y, 21.5f * (0.6f + 1.2f * f), stroke)
    }

    private fun step2(c: Canvas) {
        val col = 0xFFCFC8BA.toInt()
        val at = page(c, listOf(title(), gap(14f)) + lines(col, 1f, .92f, .96f, .6f) + gap(22f) + lines(col, .98f, .88f, .94f, .4f))
        // Like the real thing: the box stays on a paragraph while it's read, goes, then appears on the next.
        val paras = listOf(at.subList(2, 6) to (0f to .46f), at.subList(7, 11) to (.52f to .98f))
        for ((para, span) in paras) {
            val (start, end) = span
            if (t < start || t > end) continue
            val a = kf(t, start, 0f, start + .03f, 1f, end - .03f, 1f, end, 0f)
            highlight(c, around(para), 10f, a)
            // The underline steps along each line, word by word.
            val words = WORDS
            val readStart = start + .04f
            val readEnd = end - .05f
            val i = (((t - readStart) / (readEnd - readStart)) * words.size).toInt().coerceIn(0, words.size - 1)
            if (t >= readStart) {
                val (line, x, w) = words[i]
                val l = para[line]
                val right = minOf(l.left + x + w, l.right)
                p.color = Paper.UNDERLINE; p.alpha = (a * 255).toInt()
                r.set(l.left + x, l.bottom + 2f, right, l.bottom + 5f); c.drawRoundRect(r, 1.5f, 1.5f, p)
                p.alpha = 255
            }
        }
        // Playback controls, with the speaker's sound bars moving.
        val y = 428f - 26f - 17f
        val pale = 0xFFE6EEFC.toInt()
        circleIcon(c, 81f, y, 15f, R.drawable.ic_previous, pale, Paper.BLUE, 13f)
        circleIcon(c, 117f, y, 15f, R.drawable.ic_next, pale, Paper.BLUE, 13f)
        circleIcon(c, 153f, y, 15f, R.drawable.ic_stop, pale, Paper.BLUE, 13f)
        p.color = Paper.BLUE; c.drawCircle(191f, y, 17f, p)
        p.color = 0xFFFFFFFF.toInt()
        for (i in 0..2) {
            val phase = ((elapsed - i * 150L).mod(900L)) / 900f
            val h = 6f + 12f * (0.5f - 0.5f * cos(2 * PI.toFloat() * phase))
            val x = 191f - 5f + i * 5f
            r.set(x - 1.5f, y - h / 2, x + 1.5f, y + h / 2); c.drawRoundRect(r, 1.5f, 1.5f, p)
        }
    }

    private fun step3(c: Canvas) {
        val col = 0xFFCFC8BA.toInt()
        val at = page(c, listOf(Block(60f, 1f, 0xFFE9E3D7.toInt(), 10f), gap(6f)) + lines(col, 1f, .92f, .96f, .6f) +
            gap(14f) + lines(col, .98f, .88f, .94f, .4f), top = 50f)
        val para = at.subList(2, 6)
        highlight(c, around(para), 10f, kf(t, 0f, 0f, .52f, 0f, .6f, 1f, .92f, 1f, 1f, 0f))
        val dy = para.first().top - 116f
        val path = Path().apply {
            moveTo(30f, 128f + dy); cubicTo(70f, 110f + dy, 170f, 112f + dy, 196f, 126f + dy)
            cubicTo(222f, 140f + dy, 204f, 168f + dy, 170f, 176f + dy); cubicTo(136f, 184f + dy, 60f, 182f + dy, 22f, 170f + dy)
            cubicTo(-16f, 158f + dy, 10f, 134f + dy, 40f, 124f + dy)
        }
        val pa = kf(t, 0f, 1f, .48f, 1f, .58f, 0f)
        if (pa > 0) pen(c, path, kf(t, 0f, 0f, .08f, 0f, .48f, 1f), 3f, Paper.BLUE, (pa * 255).toInt())
        // The area tools: pen (chosen) and close.
        circleIcon(c, 27f, 27f, 15f, R.drawable.ic_draw, Paper.BLUE, 0xFFFFFFFF.toInt(), 14f)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(63f, 27f, 15f, p)
        stroke.color = Paper.TRACK; stroke.strokeWidth = 1f; stroke.alpha = 255; c.drawCircle(63f, 27f, 14.5f, stroke)
        val x = cachedIcon(R.drawable.ic_close, Paper.INK)
        x.setBounds(57, 21, 69, 33); x.draw(c)
    }

    private fun step4(c: Canvas) {
        val col = 0xFFCFC8BA.toInt()
        val at = page(c, listOf(title(), gap(14f)) + lines(col, 1f, .92f) + gap(22f) + lines(col, .96f, .84f) + gap(22f) + lines(col, .98f, .88f))
        val sel = at.subList(5, 7)
        val grow = kf(t, 0f, 0f, .1f, 0f, .34f, 1f)
        p.color = 0x4D2D6CDF
        for (line in sel) if (grow > 0) { r.set(line.left - 2, line.top - 4, line.left - 2 + (line.width() + 4) * grow, line.top + 11); c.drawRoundRect(r, 3f, 3f, p) }
        highlight(c, around(sel, 8f), 8f, kf(t, 0f, 0f, .74f, 0f, .8f, 1f, .96f, 1f, 1f, 0f))
        val a = kf(t, 0f, 0f, .36f, 0f, .44f, 1f, .7f, 1f, .76f, 0f)
        val dy = kf(t, 0f, 8f, .36f, 8f, .44f, 0f)
        val pick = kf(t, 0f, 0f, .54f, 0f, .6f, 1f, .72f, 1f, .78f, 0f)
        menu(c, 14f, sel.first().top - 42f + dy, listOf("Copy", "Share", "Read with Wafflr"), 2, a, 10f, pick = pick)
    }

    // --- drawing helpers ---------------------------------------------------------------------

    private fun bars(c: Canvas, x: Float, top: Float, w: Float, h: Float, gap: Float, color: Int, vararg fracs: Float) {
        var y = top
        p.color = color
        for (f in fracs) {
            if (f < 0) { y += -f; continue }
            r.set(x, y, x + w * f, y + h); c.drawRoundRect(r, h / 2, h / 2, p)
            y += h + gap
        }
    }

    private fun highlight(c: Canvas, box: RectF, radius: Float, alpha: Float = 1f) {
        if (alpha <= 0) return
        p.color = Paper.AMBER_FILL; p.alpha = (0x38 * alpha).toInt()
        c.drawRoundRect(box, radius, radius, p)
        stroke.color = Paper.AMBER; stroke.strokeWidth = 2f; stroke.alpha = (255 * alpha).toInt()
        c.drawRoundRect(box, radius, radius, stroke)
        p.alpha = 255
    }

    private fun pen(c: Canvas, path: Path, progress: Float, width: Float, color: Int, alpha: Int) {
        if (progress <= 0) return
        val m = PathMeasure(path, false)
        seg.reset()
        m.getSegment(0f, progress * m.length, seg, true)
        stroke.color = color; stroke.strokeWidth = width; stroke.alpha = alpha
        c.drawPath(seg, stroke)
    }

    private fun circleIcon(c: Canvas, cx: Float, cy: Float, radius: Float, icon: Int, bg: Int, tint: Int, size: Float, alpha: Float = 1f) {
        if (alpha <= 0) return
        p.color = bg; p.alpha = (255 * alpha).toInt()
        c.drawCircle(cx, cy, radius, p)
        p.alpha = 255
        val ic = cachedIcon(icon, tint)
        ic.alpha = (255 * alpha).toInt()
        ic.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        ic.draw(c)
        ic.alpha = 255
    }

    /** A pretend text-selection menu. [pickIndex] lights up in blue as [pick] goes to 1. */
    private fun menu(c: Canvas, x: Float, y: Float, items: List<String>, pickIndex: Int, alpha: Float, size: Float, pick: Float = 0f, separator: Boolean = false) {
        if (alpha <= 0) return
        tp.typeface = Paper.sans(context, 600); tp.textSize = size
        val pad = size * 0.7f
        val widths = items.map { tp.measureText(it) + pad * 2 }
        val sepW = if (separator) tp.measureText("·") + 2f else 2f
        val w = widths.sum() + sepW * (items.size - 1) + 8f
        val h = size + 12f + 8f
        r.set(x, y, x + w, y + h)
        p.color = 0xFFFFFFFF.toInt(); p.alpha = (255 * alpha).toInt()
        p.setShadowLayer(9f, 0f, 3f, ((0x2E * alpha).toInt() shl 24) or 0x1E1B16)
        c.drawRoundRect(r, 10f, 10f, p)
        p.clearShadowLayer()
        var ix = x + 4f
        for ((i, label) in items.withIndex()) {
            val iw = widths[i]
            val picked = i == pickIndex && pick > 0
            if (picked) {
                p.color = Paper.BLUE; p.alpha = (255 * alpha * pick).toInt()
                r.set(ix, y + 4f, ix + iw, y + h - 4f); c.drawRoundRect(r, 6f, 6f, p)
            }
            tp.color = if (picked && pick > 0.5f) 0xFFFFFFFF.toInt() else Paper.INK
            tp.alpha = (255 * alpha).toInt()
            c.drawText(label, ix + pad, y + h / 2 + size * 0.35f, tp)
            ix += iw
            if (i < items.size - 1) {
                if (separator) { tp.color = Paper.INK; c.drawText("·", ix + sepW / 2 - tp.measureText("·") / 2, y + h / 2 + size * 0.35f, tp) }
                ix += sepW
            }
        }
        p.alpha = 255
    }

    /** A note on the panel. A negative [y] measures from the bottom. */
    private fun callout(c: Canvas, text: String, x: Float, y: Float, dark: Boolean, maxW: Float = width - 44 * d) {
        tp.typeface = Paper.sans(context, 500)
        tp.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, if (dark) 13f else 12f, resources.displayMetrics)
        tp.color = if (dark) Paper.BG else Paper.INK
        tp.alpha = 255
        val textW = min(tp.measureText(text), maxW).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, textW).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val padX = 12 * d
        val padY = 8 * d
        val w = layout.width + padX * 2
        val h = layout.height + padY * 2
        val top = if (y < 0) height + y - h else y
        r.set(x, top, x + w, top + h)
        p.color = if (dark) Paper.INK else Paper.CARD
        if (!dark) p.setShadowLayer(16 * d / 2, 0f, 6 * d / 2, 0x1F1E1B16)
        c.drawRoundRect(r, 12 * d, 12 * d, p)
        p.clearShadowLayer()
        c.save(); c.translate(x + padX, top + padY); layout.draw(c); c.restore()
    }

    companion object {
        /** The frame shown when animations are turned off: one that explains the step. */
        private val STILL = mapOf(
            Kind.CARD_READ to 0.2f, Kind.CARD_DRAW to 0.7f, Kind.CARD_SELECT to 0.6f,
            Kind.STEP1 to 0.5f, Kind.STEP2 to 0.25f, Kind.STEP3 to 0.4f, Kind.STEP4 to 0.6f,
        )

        private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        /** Pretend words in a paragraph, as (line, x, width), in reading order. */
        private val WORDS = listOf(
            Triple(0, 0f, 30f), Triple(0, 36f, 44f), Triple(0, 86f, 28f), Triple(0, 120f, 56f),
            Triple(1, 0f, 40f), Triple(1, 46f, 30f), Triple(1, 82f, 50f), Triple(1, 138f, 26f),
            Triple(2, 0f, 34f), Triple(2, 40f, 52f), Triple(2, 98f, 36f), Triple(2, 140f, 32f),
            Triple(3, 0f, 44f), Triple(3, 50f, 56f),
        )

        /**
         * Value at time [t] from keyframes given as (time, value) pairs, eased in and out between
         * them and held before the first and after the last.
         */
        fun kf(t: Float, vararg k: Float): Float {
            if (t <= k[0]) return k[1]
            var i = 0
            while (i + 2 < k.size) {
                val t0 = k[i]; val v0 = k[i + 1]; val t1 = k[i + 2]; val v1 = k[i + 3]
                if (t <= t1) {
                    val x = if (t1 > t0) (t - t0) / (t1 - t0) else 1f
                    val e = x * x * (3 - 2 * x)
                    return v0 + (v1 - v0) * e
                }
                i += 2
            }
            return k[k.size - 1]
        }
    }
}
