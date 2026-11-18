package app.wafflr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Phase 0 test screen. Two experiments:
 *  1. Voice speed: how quickly each Piper and Kokoro voice starts talking on this phone.
 *  2. Selection probe: which apps report selected text (see SelectionProbeService).
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var voiceSpinner: Spinner
    private lateinit var packStatus: TextView
    private lateinit var input: EditText
    private lateinit var speedLabel: TextView
    private lateinit var threadsSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var probeStatus: TextView
    private lateinit var log: TextView
    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }
    private val speech get() = WafflrApp.instance.speech
    private val store get() = WafflrApp.instance.voices

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        root.addView(heading("Wafflr · Phase 0 tests"))
        root.addView(body(SpeechEngine.deviceLine()))

        // --- Voice test -------------------------------------------------------------------
        root.addView(heading("1. Voice speed test"))
        root.addView(body("Pick a voice and tap Speak. Kokoro and Jenny download once (use Wi-Fi), then work offline. Try each voice in airplane mode too."))

        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VoiceCatalog.voices.map { it.label })
            setSelection(VoiceCatalog.voices.indexOf(prefs.voice))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.voice = VoiceCatalog.voices[pos]
                    refreshPack()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(voiceSpinner)
        packStatus = body("")
        root.addView(packStatus)

        input = EditText(this).apply {
            setText(SAMPLE)
            minLines = 4
            textSize = 15f
        }
        root.addView(input)

        speedLabel = body("")
        root.addView(speedLabel)
        root.addView(SeekBar(this).apply {
            max = 15 // 0.5 .. 2.0 in 0.1 steps
            progress = ((prefs.speed - 0.5f) * 10).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.speed = 0.5f + p / 10f
                    updateSpeedLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        updateSpeedLabel()

        root.addView(body("CPU threads used for speech"))
        val threadOptions = listOf(1, 2, 4, 6)
        threadsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, threadOptions.map { "$it threads" })
            setSelection(threadOptions.indexOf(prefs.threads).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.threads = threadOptions[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(threadsSpinner)

        root.addView(row(
            button("Speak") { speak() },
            button("Stop") { speech.stop(); status.text = "Stopped" },
        ))
        status = body("Ready")
        root.addView(status)

        root.addView(heading("Results"))
        results = mono("")
        root.addView(results)
        root.addView(row(
            button("Copy results") { copy("Wafflr voice results", report()) },
            button("Clear") { prefs.results = ""; refreshResults() },
        ))

        // --- Selection probe ----------------------------------------------------------------
        root.addView(heading("2. Text selection test"))
        root.addView(body(
            "a) Turn on the Wafflr selection probe in Accessibility settings. On a sideloaded app, Android may block this the first time: open App info for Wafflr, tap the ⋮ menu, choose 'Allow restricted settings', then try again.\n" +
            "b) In Chrome, Gmail, Google News and a PDF, long-press to select some text. Each selection should add a line below.\n" +
            "c) Also tap 'Read aloud' in the selection menu (it may be under ⋮). It reads with the voice chosen above."
        ))
        probeStatus = body("")
        root.addView(probeStatus)
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        log = mono("")
        root.addView(log)
        root.addView(row(
            button("Copy log") { copy("Wafflr selection log", report()) },
            button("Clear log") { SelectionLog.clear() },
        ))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshResults()
    }

    override fun onResume() {
        super.onResume()
        SelectionLog.addListener(logListener)
        refreshLog()
        refreshPack()
        refreshResults()
        probeStatus.text = if (isProbeEnabled()) "Selection probe: ON" else "Selection probe: OFF"
    }

    override fun onPause() {
        SelectionLog.removeListener(logListener)
        super.onPause()
    }

    private fun speak() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        status.text = "Starting…"
        speech.speak(text, prefs.voice, prefs.speed, prefs.threads, object : SpeechListener {
            override fun onStatus(text: String) = runOnUiThread { status.text = text }
            override fun onDone(result: SpeechResult) = runOnUiThread {
                status.text = if (result.stopped) "Stopped" else "Done"
                prefs.results = result.summary() + "\n\n" + prefs.results
                refreshResults()
                refreshPack()
            }
            override fun onError(message: String) = runOnUiThread { status.text = "Error: $message" }
        })
    }

    private fun refreshPack() {
        val pack = prefs.voice.pack
        packStatus.text = when {
            store.isInstalled(pack) -> "Voice files: installed, works offline"
            pack.bundled -> "Voice files: built in (unpacked on first use)"
            else -> "Voice files: not downloaded yet (${pack.sizeNote}). Tap Speak to download."
        }
    }

    private fun refreshResults() {
        results.text = prefs.results.ifEmpty { "No runs yet." }
    }

    private fun refreshLog() {
        log.text = SelectionLog.read().ifEmpty { "No selections logged yet." }
    }

    private fun updateSpeedLabel() {
        speedLabel.text = "Speed: %.1f×".format(prefs.speed)
    }

    private fun report(): String = buildString {
        append("Wafflr Phase 0 report\n")
        append(SpeechEngine.deviceLine()).append("\n")
        append("App version ").append(packageManager.getPackageInfo(packageName, 0).versionName).append("\n\n")
        append("VOICE RESULTS\n").append(prefs.results.ifEmpty { "none\n" }).append("\n")
        append("SELECTION LOG\n").append(SelectionLog.read().ifEmpty { "none\n" })
    }

    private fun isProbeEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, SelectionProbeService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied. Paste it into the chat.", Toast.LENGTH_SHORT).show()
    }

    // --- tiny view helpers ------------------------------------------------------------------
    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun mono(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    companion object {
        const val SAMPLE =
            "The morning train was late again, so Sarah decided to walk. It was only two miles, " +
            "and the rain had finally stopped. Along the way, she noticed things she'd never seen " +
            "from the carriage window: a bakery with a hand-painted sign, an old church clock stuck " +
            "at a quarter past three, and a fox trotting calmly across the park as if it owned the place."
    }
}
