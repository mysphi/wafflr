package app.wafflr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Settings: turning Wafflr on, the waffle button, what to read, and the test report. */
class SettingsActivity : WafflrScreen() {
    private lateinit var offCard: LinearLayout
    private lateinit var shortcutCard: LinearLayout
    private lateinit var bubbleSwitch: PaperSwitch
    private lateinit var reportCard: LinearLayout
    private lateinit var results: TextView
    private lateinit var crash: TextView
    private lateinit var preview: ButtonPreview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = page()
        root.addView(backHeader("Settings"))

        // --- Turn on (only while it's off) ---------------------------------------------------------
        offCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Paper.OFF_BG, 20f)
            setPadding(16.px, 16.px, 16.px, 16.px)
            addView(text("Turn on Wafflr", 18f, Paper.OFF_INK, 500, serif = true))
            addView(text(
                "Wafflr needs its Accessibility switch to float over other apps and read the screen. " +
                    "If the switch is greyed out: Settings, Apps, Wafflr, the ⋮ menu, Allow restricted settings, then try again.",
                14f, Paper.OFF_INK, lineSpacing = 1.3f,
            ).apply { setPadding(0, 6.px, 0, 12.px) })
            addView(pillButton("Open Accessibility settings", Look.BLUE) { openAccessibility() })
        }
        root.addView(offCard, margins(8))

        // --- Android's extra shortcut (only while it's on) -------------------------------------------
        shortcutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Paper.OFF_BG, 20f)
            setPadding(16.px, 16.px, 16.px, 16.px)
            addView(text(
                "Android's own accessibility shortcut is also on for Wafflr. That's the small Wafflr icon in a grey tab " +
                    "at the edge of the screen. Wafflr doesn't need it, and it gets in the way of the waffle. To remove it: " +
                    "Accessibility settings, Wafflr, turn off \"Wafflr shortcut\".",
                14f, Paper.OFF_INK, lineSpacing = 1.3f,
            ).apply { setPadding(0, 0, 0, 12.px) })
            addView(pillButton("Turn off the extra shortcut", Look.DARK) { openAccessibility() })
        }
        root.addView(shortcutCard, margins(8))

        // --- Waffle button ----------------------------------------------------------------------------
        root.addView(sectionLabel("Waffle button"))
        preview = ButtonPreview()
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.px, 0, 0, 0) }
        sliders.addView(intSlider("Size", FloatingBubble.MIN_SIZE, FloatingBubble.MAX_SIZE, 2, prefs.bubbleSize,
            { if (it == 38) "Normal" else if (it < 38) "Smaller" else "Larger" }, { preview.size = it }) { prefs.bubbleSize = it })
        sliders.addView(intSlider("Gap from edge", 0, FloatingBubble.MAX_EDGE, 2, prefs.bubbleEdge,
            { if (it == 0) "Touching" else "$it dp" }, { preview.gap = it }) { prefs.bubbleEdge = it })
        root.addView(card(horizontal = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(84.px, 112.px))
            addView(sliders, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, margins())
        root.addView(card(padding = 0).apply {
            val (showRow, sw) = switchRow("Show the waffle button", "Floats at the edge of every app", prefs.bubbleEnabled) { prefs.bubbleEnabled = it }
            bubbleSwitch = sw
            addView(showRow)
            addView(divider())
            addView(switchRow("Tuck into the edge", "Slides away when you're not using it. Tap what's showing to bring it back.", prefs.bubbleTuck) {
                prefs.bubbleTuck = it
            }.first)
        }, margins(8))

        // --- Also read ----------------------------------------------------------------------------------
        root.addView(sectionLabel("Also read"))
        root.addView(note("Wafflr reads the main text of a page. Turn these on to hear more.", 13f), margins().apply { bottomMargin = 8.px })
        root.addView(Flow().apply {
            addView(chip("Headings", prefs.readHeadings) { prefs.readHeadings = it })
            addView(chip("Menus and buttons", prefs.readMenus) { prefs.readMenus = it })
            addView(chip("Picture descriptions", prefs.readPictures) { prefs.readPictures = it })
            addView(chip("Dates and details", prefs.readDetails) { prefs.readDetails = it })
            addView(chip("Text above the story", prefs.readLeadIn) { prefs.readLeadIn = it })
        }, margins())

        // --- While reading -----------------------------------------------------------------------------
        root.addView(sectionLabel("While reading"))
        root.addView(card(padding = 0).apply {
            addView(switchRow("Highlight the paragraph", null, prefs.highlight) { prefs.highlight = it }.first)
            addView(divider())
            addView(switchRow("Underline each word", "In apps that say where their words are", prefs.underlineWords) { prefs.underlineWords = it }.first)
        }, margins())
        root.addView(note(
            "Headphone buttons and the lock screen can pause, skip and stop. For a quick start, add the Wafflr tile " +
                "to Quick Settings: pull down the shade, tap the pencil, and drag Wafflr in.", 13f,
        ), margins(12))

        // --- Test report and the Accessibility switch ------------------------------------------------------
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(pillButton("Test report", Look.OUTLINE, radius = 12f) {
                reportCard.visibility = if (reportCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }, lp())
            addView(pillButton("Accessibility switch", Look.OUTLINE, radius = 12f) { openAccessibility() }, lp().apply { marginStart = 8.px })
        }, margins(18))

        reportCard = card().apply {
            visibility = View.GONE
            crash = mono("")
            results = mono("")
            addView(crash)
            addView(results)
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10.px, 0, 0)
                val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                addView(pillButton("Copy report", Look.DARK, radius = 12f) { copyReport() }, lp())
                addView(pillButton("Clear", Look.CREAM, radius = 12f) { prefs.results = ""; refreshResults() }, lp().apply { marginStart = 8.px })
            })
            addView(text("Advanced: CPU threads used for speech", 13f, Paper.MUTED).apply { setPadding(0, 14.px, 0, 8.px) })
            val flow = Flow()
            fun fill() {
                flow.removeAllViews()
                for (n in listOf(1, 2, 4, 6)) flow.addView(chip("$n threads", prefs.threads == n) { prefs.threads = n; fill() })
            }
            fill()
            addView(flow)
        }
        root.addView(reportCard, margins(8))

        setContentView(scroll(root))
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        offCard.visibility = if (on) View.GONE else View.VISIBLE
        bubbleSwitch.isEnabled = on
        shortcutCard.visibility = if (systemShortcutOn()) View.VISIBLE else View.GONE
        refreshResults()
        val lastCrash = CrashReport.lastCrash(this)
        crash.text = if (lastCrash.isEmpty()) "" else "Last time the app closed unexpectedly:\n$lastCrash\n(Included in the test report.)"
        crash.visibility = if (lastCrash.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** A slider for a whole number. Shows changes as you drag; saves when let go (the waffle rebuilds then). */
    private fun intSlider(
        title: String, min: Int, max: Int, step: Int, value: Int,
        describe: (Int) -> String, live: (Int) -> Unit, set: (Int) -> Unit,
    ): View {
        lateinit var shown: TextView
        val bar = seekBar((max - min) / step, (value - min) / step, { p, _ ->
            shown.text = describe(min + p * step)
            live(min + p * step)
        }, { p -> set(min + p * step) })
        val (block, valueText) = labelledSlider(title, bar)
        shown = valueText
        shown.text = describe(value)
        live(value)
        return block
    }

    /** True if Android's floating accessibility button is set to launch Wafflr. */
    private fun systemShortcutOn(): Boolean = try {
        val targets = Settings.Secure.getString(contentResolver, "accessibility_button_targets").orEmpty()
        targets.contains(packageName)
    } catch (_: Throwable) {
        false
    }

    private fun refreshResults() {
        results.text = prefs.results.ifEmpty { "Nothing read yet." }
    }

    private fun copyReport() {
        val report = buildString {
            append("Wafflr test report\n")
            append(Models.deviceLine()).append("\n")
            append("App version ").append(packageManager.getPackageInfo(packageName, 0).versionName).append("\n")
            append("Accessibility service: ").append(if (isServiceEnabled()) "on" else "off").append("\n\n")
            append(prefs.results.ifEmpty { "Nothing read yet." }).append("\n")
            val lastCrash = CrashReport.lastCrash(this@SettingsActivity)
            if (lastCrash.isNotEmpty()) append("\nLAST CRASH\n").append(lastCrash).append("\n")
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Wafflr report", report))
        Toast.makeText(this, "Copied. Paste it into the chat.", Toast.LENGTH_SHORT).show()
    }

    /** A little screen with the waffle at its edge, sized and spaced like the real one. */
    private inner class ButtonPreview : View(this@SettingsActivity) {
        var size = prefs.bubbleSize
            set(v) { field = v; invalidate() }
        var gap = prefs.bubbleEdge
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(c: Canvas) {
            // The preview is a phone at about a fifth of real size.
            val k = width / (412f * dp)
            paint.color = 0xFFEFE6D3.toInt()
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(box, 14 * dp, 14 * dp, paint)
            paint.color = 0xFFCDBFA4.toInt()
            for (i in 0 until 6) {
                val y = (16 + i * 11) * dp
                box.set(10 * dp, y, width - (if (i % 3 == 2) 34 else 16) * dp, y + 5 * dp)
                c.drawRoundRect(box, 2.5f * dp, 2.5f * dp, paint)
            }
            val r = size * dp * k * 1.6f / 2
            val cx = width - gap * dp * k * 1.6f - r
            paint.color = Paper.BLUE
            c.drawCircle(cx, height * 0.55f, r.coerceAtLeast(4 * dp), paint)
        }
    }

    /** Lays out children left to right, wrapping onto new lines (for the chips). */
    private inner class Flow : ViewGroup(this@SettingsActivity) {
        private val gapPx = 8.px

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxW = MeasureSpec.getSize(widthSpec)
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gapPx; rowH = 0 }
                x += ch.measuredWidth + gapPx
                rowH = maxOf(rowH, ch.measuredHeight)
            }
            setMeasuredDimension(maxW, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gapPx; rowH = 0 }
                ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
                x += ch.measuredWidth + gapPx
                rowH = maxOf(rowH, ch.measuredHeight)
            }
        }
    }
}
