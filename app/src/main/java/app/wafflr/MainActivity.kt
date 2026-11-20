package app.wafflr

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Home: whether Wafflr is on, the how-to cards, the voice, and a place to try it. */
class MainActivity : WafflrScreen(), ReaderListener {
    private lateinit var statusPill: TextView
    private lateinit var voiceAvatarSlot: FrameLayout
    private lateinit var voiceName: TextView
    private lateinit var voiceAccent: TextView
    private lateinit var sampleButton: FrameLayout
    private lateinit var speedButton: TextView
    private lateinit var toneButton: TextView
    private lateinit var speedPanel: LinearLayout
    private lateinit var tonePanel: LinearLayout
    private lateinit var trySub: TextView
    private lateinit var listenButton: TextView
    private lateinit var input: EditText
    private val reader get() = app.reader

    /** What this screen started reading: "Sample", "Try it" or nothing. */
    private var mine: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = page()

        // --- Header: logo, name and settings -----------------------------------------------
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.px, 20.px, 16.px, 8.px)
            addView(FrameLayout(this@MainActivity).apply {
                background = oval(Paper.BLUE)
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_wafflr) },
                    FrameLayout.LayoutParams(18.px, 18.px, Gravity.CENTER))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(34.px, 34.px))
            addView(text("Wafflr", 22f, weight = 600, serif = true).apply { setPadding(10.px, 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_settings, Paper.INK, Paper.CARD, "Settings") { openSettings() }.apply {
                background = pressable(shape(Paper.CARD, 999f, 1.px, Paper.CHIP))
            })
        })

        // --- On or off ------------------------------------------------------------------------
        statusPill = text("", 13f, weight = 500).apply {
            compoundDrawablePadding = 8.px
            setPadding(12.px, 6.px, 12.px, 6.px)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(statusPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(20.px, 4.px, 20.px, 0)
        })

        // --- Hero -----------------------------------------------------------------------------
        root.addView(text("Any screen,\nread aloud.", 36f, weight = 500, serif = true, lineSpacing = 1.0f).apply {
            letterSpacing = -0.02f
        }, margins(18))
        root.addView(note("Tap the waffle in any app. Wafflr reads the text and follows along with a highlight.", 15f), margins(10))

        // --- See how it works ------------------------------------------------------------------
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.px, 22.px, 12.px, 4.px)
            addView(text("See how it works", 15f, weight = 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Watch all", 14f, Paper.BLUE, 500).apply {
                gravity = Gravity.CENTER
                minHeight = 44.px
                setPadding(8.px, 0, 8.px, 0)
                background = pressable(shape(0x00000000, 999f))
                setOnClickListener { learn(0) }
            })
        })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(20.px, 0, 8.px, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(howCard("Tap the waffle", "Open the controls", 0xFFEFE6D3.toInt(), DemoView.Kind.CARD_WAFFLE, 0))
                addView(howCard("Read the screen", "Whole page", 0xFFDCE7F7.toInt(), DemoView.Kind.CARD_READ, 1))
                addView(howCard("Draw around text", "Just one part", 0xFFE4EEDF.toInt(), DemoView.Kind.CARD_DRAW, 2))
                addView(howCard("Select and read", "From the menu", 0xFFF4DFD3.toInt(), DemoView.Kind.CARD_SELECT, 3))
            })
        })

        // --- Voice ----------------------------------------------------------------------------
        val voiceCard = card()
        voiceAvatarSlot = FrameLayout(this)
        voiceName = text("", 16f, weight = 600)
        voiceAccent = text("", 13f, Paper.MUTED)
        sampleButton = iconButton(R.drawable.ic_play, 0xFFFFFFFF.toInt(), Paper.BLUE, "Play a sample", iconSize = 20) { onSample() }
        voiceCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(voiceAvatarSlot, LinearLayout.LayoutParams(44.px, 44.px))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.px, 0, 8.px, 0)
                addView(text("Voice", 12f, Paper.SOFT))
                addView(voiceName)
                addView(voiceAccent)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sampleButton)
        })
        speedButton = pillButton("", Look.CREAM, radius = 12f) { toggle(speedPanel) }
        toneButton = pillButton("Tone", Look.CREAM, radius = 12f) { toggle(tonePanel) }
        voiceCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14.px, 0, 0)
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(speedButton, lp())
            addView(toneButton, lp().apply { setMargins(8.px, 0, 8.px, 0) })
            addView(pillButton("Voices", Look.DARK, radius = 12f) { startActivity(Intent(this@MainActivity, VoicesActivity::class.java)) }, lp())
        })

        // Speed: 0.5 .. 2.0 in 0.1 steps.
        speedPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 14.px, 0, 0) }
        lateinit var speedValue: TextView
        val speedSlider = seekBar(15, ((prefs.speed - 0.5f) * 10).toInt(), { p, user ->
            if (user) prefs.speed = 0.5f + p / 10f
            speedValue.text = "%.1f×".format(0.5f + p / 10f)
            updateSpeedButton()
        })
        labelledSlider("Speed", speedSlider).let { (block, value) -> speedValue = value; speedPanel.addView(block) }
        speedValue.text = "%.1f×".format(prefs.speed)
        voiceCard.addView(speedPanel)

        // Tone: pitch 0.70 .. 1.30 and expression 0.20 .. 1.00, both in 0.05 steps.
        tonePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 14.px, 0, 0) }
        lateinit var pitchValue: TextView
        lateinit var exprValue: TextView
        fun pitchText(v: Float) = when { v < 0.99f -> "Lower"; v > 1.01f -> "Higher"; else -> "Normal" }
        fun exprText(v: Float) = when { v < 0.55f -> "Steadier"; v > 0.8f -> "Livelier"; else -> "Normal" }
        val pitchSlider = seekBar(12, Math.round((prefs.pitch - 0.70f) / 0.05f), { p, user ->
            val v = 0.70f + p * 0.05f
            if (user) prefs.pitch = v
            pitchValue.text = pitchText(v)
        })
        val exprSlider = seekBar(16, Math.round((prefs.expression - 0.20f) / 0.05f), { p, user ->
            val v = 0.20f + p * 0.05f
            if (user) prefs.expression = v
            exprValue.text = exprText(v)
        })
        labelledSlider("Pitch", pitchSlider).let { (block, value) -> pitchValue = value; tonePanel.addView(block) }
        labelledSlider("Expression", exprSlider).let { (block, value) -> exprValue = value; tonePanel.addView(block) }
        pitchValue.text = pitchText(prefs.pitch)
        exprValue.text = exprText(prefs.expression)
        tonePanel.addView(text("Reset to normal", 14f, Paper.BLUE, 500).apply {
            minHeight = 44.px
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                prefs.pitch = 1.0f
                prefs.expression = Tuning.DEFAULT_EXPRESSION
                pitchSlider.progress = 6
                exprSlider.progress = Math.round((Tuning.DEFAULT_EXPRESSION - 0.20f) / 0.05f)
            }
        })
        voiceCard.addView(tonePanel)
        root.addView(voiceCard, margins(18))

        // --- Try it here ------------------------------------------------------------------------
        val tryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Paper.INK, 20f)
            setPadding(16.px, 16.px, 16.px, 16.px)
        }
        trySub = text("Hear your voice read a short passage.", 13f, Paper.DARK_SUB)
        listenButton = pillButton("Listen", Look.LIGHT) { onTry() }
        tryCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Try it here", 18f, Paper.BG, 500, serif = true))
                addView(trySub.apply { setPadding(0, 3.px, 8.px, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(listenButton)
        })
        input = EditText(this).apply {
            setText(SAMPLE)
            textSize = 15f
            typeface = Paper.sans(this@MainActivity)
            setTextColor(Paper.INK)
            setLineSpacing(0f, 1.3f)
            background = shape(Paper.BG, 14f)
            setPadding(14.px, 12.px, 14.px, 12.px)
            minLines = 3
            visibility = View.GONE
        }
        val edit = text("Use your own words", 13f, Paper.BG, 500).apply {
            minHeight = 40.px
            gravity = Gravity.CENTER_VERTICAL
            paint.isUnderlineText = true
        }
        edit.setOnClickListener {
            val show = input.visibility != View.VISIBLE
            input.visibility = if (show) View.VISIBLE else View.GONE
            edit.text = if (show) "Hide the text" else "Use your own words"
            if (show) input.requestFocus()
        }
        tryCard.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.px })
        tryCard.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.px })
        root.addView(tryCard, margins(12))

        setContentView(scroll(root))

        // The walkthrough opens by itself the first time.
        if (savedInstanceState == null && !prefs.seenTour) {
            prefs.seenTour = true
            learn(0)
        }
    }

    override fun onResume() {
        super.onResume()
        reader.addListener(this)
        refreshStatus()
        refreshVoice()
        updateSpeedButton()
        onStatus(reader.status)
    }

    override fun onPause() {
        reader.removeListener(this)
        super.onPause()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun learn(step: Int) = startActivity(Intent(this, LearnActivity::class.java).putExtra(LearnActivity.EXTRA_STEP, step))

    private fun howCard(title: String, sub: String, bg: Int, kind: DemoView.Kind, step: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        contentDescription = "$title. Watch how"
        setOnClickListener { learn(step) }
        addView(FrameLayout(this@MainActivity).apply {
            background = pressable(shape(bg, 18f))
            clipToOutline = true
            addView(DemoView(this@MainActivity, kind).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(150.px, 168.px))
        addView(text(title, 13f, weight = 600).apply { setPadding(2.px, 8.px, 2.px, 0) })
        addView(text(sub, 12f, Paper.SOFT).apply { setPadding(2.px, 2.px, 2.px, 0) })
        layoutParams = LinearLayout.LayoutParams(150.px, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12.px }
    }

    private fun toggle(panel: LinearLayout) {
        val open = panel.visibility != View.VISIBLE
        speedPanel.visibility = View.GONE
        tonePanel.visibility = View.GONE
        if (open) panel.visibility = View.VISIBLE
        for ((button, p) in listOf(speedButton to speedPanel, toneButton to tonePanel)) {
            val on = p.visibility == View.VISIBLE
            button.background = pressable(shape(if (on) Paper.BLUE_PALE else Paper.BG, 12f, 1.px, if (on) Paper.BLUE_DARK else Paper.CHIP))
            button.setTextColor(if (on) Paper.BLUE_DARK else Paper.INK)
        }
    }

    private fun updateSpeedButton() {
        speedButton.text = "Speed %.1f×".format(prefs.speed)
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        val (bg, fg, dot, label) = when {
            !on -> Quad(Paper.OFF_BG, Paper.OFF_INK, Paper.OFF_DOT, "Wafflr is off. Tap here to turn it on.")
            !prefs.bubbleEnabled -> Quad(Paper.OFF_BG, Paper.OFF_INK, Paper.OFF_DOT, "Wafflr is on. The waffle button is hidden.")
            else -> Quad(Paper.ON_BG, Paper.ON_INK, Paper.ON_DOT, "Wafflr is on. The waffle button is ready.")
        }
        statusPill.text = label
        statusPill.setTextColor(fg)
        statusPill.background = pressable(shape(bg, 999f))
        statusPill.setCompoundDrawablesRelativeWithIntrinsicBounds(oval(dot).apply { setSize(8.px, 8.px) }, null, null, null)
        val needsAction = !on || !prefs.bubbleEnabled
        statusPill.isClickable = needsAction
        statusPill.setOnClickListener(if (needsAction) View.OnClickListener { openSettings() } else null)
        statusPill.minHeight = if (needsAction) 44.px else 0
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: String)

    private fun refreshVoice() {
        val installed = app.voices.installedPacks()
        if (installed.isNotEmpty() && prefs.voice.pack !in installed) {
            prefs.voice = VoiceCatalog.voices.first { it.pack in installed }
        }
        val v = prefs.voice
        voiceAvatarSlot.removeAllViews()
        voiceAvatarSlot.addView(avatar(v).apply { layoutParams = FrameLayout.LayoutParams(44.px, 44.px) })
        voiceName.text = Paper.name(v)
        voiceAccent.text = Paper.accent(v)
        sampleButton.contentDescription = "Play a sample of ${Paper.name(v)}"
    }

    private fun onSample() {
        if (mine == "Sample" && reader.status.state != ReaderState.IDLE) { reader.stop(); return }
        val v = prefs.voice
        mine = "Sample"
        reader.read("Hi there, I'm ${Paper.name(v)}! Tap the waffle in any app, and I'll happily read it to you.",
            v, prefs.speed, prefs.threads, "Sample", prefs.tuning)
    }

    private fun onTry() {
        when (reader.status.state) {
            ReaderState.PLAYING, ReaderState.PAUSED -> if (mine == "Try it") reader.togglePause() else { reader.stop(); startTry() }
            ReaderState.PREPARING -> {}
            ReaderState.IDLE -> startTry()
        }
    }

    private fun startTry() {
        mine = "Try it"
        reader.read(input.text.toString(), prefs.voice, prefs.speed, prefs.threads, "Try it", prefs.tuning)
    }

    override fun onStatus(status: ReaderStatus) {
        if (status.state == ReaderState.IDLE) mine = null
        val trying = mine == "Try it"
        trySub.text = when {
            !trying -> "Hear your voice read a short passage."
            status.state == ReaderState.PREPARING -> status.message.ifEmpty { "Getting ready…" }
            status.state == ReaderState.PLAYING -> "Reading ${status.index + 1} of ${status.total}"
            status.state == ReaderState.PAUSED -> "Paused at ${status.index + 1} of ${status.total}"
            else -> "Hear your voice read a short passage."
        }
        listenButton.text = when {
            trying && status.state == ReaderState.PLAYING -> "Pause"
            trying && status.state == ReaderState.PAUSED -> "Resume"
            else -> "Listen"
        }
        val sampling = mine == "Sample" && status.state != ReaderState.IDLE
        (sampleButton.getChildAt(0) as ImageView).setImageResource(if (sampling) R.drawable.ic_stop else R.drawable.ic_play)
    }

    override fun onFinished(result: SpeechResult) {
        mine = null
        onStatus(reader.status)
    }

    override fun onError(message: String) {
        if (mine == "Try it") trySub.text = "Error: $message"
        mine = null
    }

    companion object {
        const val SAMPLE =
            "Good news: the kettle's on and the biscuits are out! Today is the perfect day to try " +
            "something new. Maybe it's a recipe with far too much cheese, a walk to the top of the " +
            "hill just to see the view, or a dance in the kitchen when nobody's watching. " +
            "Whatever you pick, I'll be right here, ready to read it out loud."
    }
}
