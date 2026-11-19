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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Set-up and settings screen, plus a place to try voices. */
class MainActivity : Activity(), ReaderListener {
    private lateinit var prefs: Prefs
    private lateinit var serviceStatus: TextView
    private lateinit var shortcutWarning: LinearLayout
    private lateinit var bubbleSwitch: Switch
    private lateinit var packStatus: TextView
    private lateinit var input: EditText
    private lateinit var speedLabel: TextView
    private lateinit var status: TextView
    private lateinit var playButton: Button
    private lateinit var results: TextView
    private lateinit var crash: TextView
    private val app get() = WafflrApp.instance
    private val reader get() = app.reader
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = app.prefs
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        root.addView(heading("Wafflr"))
        root.addView(body("Reads the text on your screen aloud, offline."))
        crash = mono("")
        root.addView(crash)

        // --- Set-up ---------------------------------------------------------------------------
        root.addView(heading("Turn on Wafflr"))
        serviceStatus = body("")
        root.addView(serviceStatus)
        root.addView(body(
            "Wafflr needs its Accessibility switch to float over other apps and read the screen. " +
            "If the switch is greyed out: Settings → Apps → Wafflr → ⋮ → Allow restricted settings, then try again."
        ))
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        bubbleSwitch = Switch(this).apply {
            text = "Show the floating button"
            textSize = 16f
            isChecked = prefs.bubbleEnabled
            setOnCheckedChangeListener { _, on -> prefs.bubbleEnabled = on }
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }
        root.addView(bubbleSwitch)
        shortcutWarning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body(
                "⚠ Android's own accessibility shortcut is also on for Wafflr. That is the small Wafflr icon " +
                "in a grey tab at the edge of the screen. Wafflr doesn't need it, and it gets in the way of the " +
                "floating controls. To remove it: Accessibility settings → Wafflr → turn off \"Wafflr shortcut\"."
            ))
            addView(button("Turn off the extra shortcut") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        root.addView(shortcutWarning)
        root.addView(body(
            "Floating controls: tap the Wafflr logo to open them; drag it to move; long-press it to open this app. " +
            "The speaker reads the whole screen, and becomes pause/resume while reading. " +
            "Beside it: draw a box or a shape around just the text you want; while reading, previous, next and stop.\n" +
            "Or choose Share → Read with Wafflr (some apps also show 'Read with Wafflr' straight in the selection menu)."
        ))

        // --- Voice ----------------------------------------------------------------------------
        root.addView(heading("Voice"))
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VoiceCatalog.voices.map { it.label })
            setSelection(VoiceCatalog.voices.indexOf(prefs.voice))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.voice = VoiceCatalog.voices[pos]
                    refreshPack()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        packStatus = body("")
        root.addView(packStatus)
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

        // --- Try it ---------------------------------------------------------------------------
        root.addView(heading("Try it"))
        input = EditText(this).apply { setText(SAMPLE); minLines = 4; textSize = 15f }
        root.addView(input)
        playButton = button("Read") { onPlay() }
        root.addView(row(playButton, button("Stop") { reader.stop() }))
        status = body("")
        root.addView(status)

        // --- Test report ----------------------------------------------------------------------
        root.addView(heading("Test report"))
        results = mono("")
        root.addView(results)
        root.addView(row(
            button("Copy report") { copyReport() },
            button("Clear") { prefs.results = ""; refreshResults() },
        ))
        root.addView(body("Advanced: CPU threads used for speech"))
        val threadOptions = listOf(1, 2, 4, 6)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, threadOptions.map { "$it threads" })
            setSelection(threadOptions.indexOf(prefs.threads).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.threads = threadOptions[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** True if Android's floating accessibility button is set to launch Wafflr. */
    private fun systemShortcutOn(): Boolean = try {
        val targets = Settings.Secure.getString(contentResolver, "accessibility_button_targets").orEmpty()
        targets.contains(packageName)
    } catch (_: Throwable) {
        false
    }

    override fun onResume() {
        super.onResume()
        reader.addListener(this)
        val on = isServiceEnabled()
        serviceStatus.text = if (on) "✓ Wafflr is on" else "✗ Wafflr is off. Turn it on in Accessibility settings (look for Wafflr)."
        bubbleSwitch.isEnabled = on
        shortcutWarning.visibility = if (systemShortcutOn()) View.VISIBLE else View.GONE
        refreshPack()
        refreshResults()
        val lastCrash = CrashReport.lastCrash(this)
        crash.text = if (lastCrash.isEmpty()) "" else "Last time the app closed unexpectedly:\n$lastCrash\n(Included in the test report.)"
        crash.visibility = if (lastCrash.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        reader.removeListener(this)
        super.onPause()
    }

    private fun onPlay() {
        when (reader.status.state) {
            ReaderState.PLAYING, ReaderState.PAUSED -> reader.togglePause()
            ReaderState.PREPARING -> {}
            ReaderState.IDLE -> reader.read(input.text.toString(), prefs.voice, prefs.speed, prefs.threads, "Try it")
        }
    }

    override fun onStatus(status: ReaderStatus) {
        this.status.text = when (status.state) {
            ReaderState.IDLE -> ""
            ReaderState.PREPARING -> status.message.ifEmpty { "Getting ready…" }
            ReaderState.PLAYING -> "Reading ${status.index + 1} of ${status.total}"
            ReaderState.PAUSED -> "Paused at ${status.index + 1} of ${status.total}"
        }
        playButton.text = when (status.state) {
            ReaderState.PLAYING -> "Pause"
            ReaderState.PAUSED -> "Resume"
            else -> "Read"
        }
    }

    override fun onFinished(result: SpeechResult) {
        refreshResults()
        refreshPack()
    }

    override fun onError(message: String) {
        status.text = "Error: $message"
        refreshResults()
        refreshPack()
    }

    private fun refreshPack() {
        val pack = prefs.voice.pack
        packStatus.text = when {
            app.voices.isInstalled(pack) -> "Voice files installed; works offline."
            pack.bundled -> "Built-in voice."
            else -> "Not downloaded yet (${pack.sizeNote}). It downloads the first time you use it."
        }
    }

    private fun refreshResults() {
        results.text = prefs.results.ifEmpty { "Nothing read yet." }
    }

    private fun updateSpeedLabel() {
        speedLabel.text = "Speed: %.1f×".format(prefs.speed)
    }

    private fun copyReport() {
        val report = buildString {
            append("Wafflr test report\n")
            append(Models.deviceLine()).append("\n")
            append("App version ").append(packageManager.getPackageInfo(packageName, 0).versionName).append("\n")
            append("Accessibility service: ").append(if (isServiceEnabled()) "on" else "off").append("\n\n")
            append(prefs.results.ifEmpty { "Nothing read yet." }).append("\n")
            val lastCrash = CrashReport.lastCrash(this@MainActivity)
            if (lastCrash.isNotEmpty()) append("\nLAST CRASH\n").append(lastCrash).append("\n")
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Wafflr report", report))
        Toast.makeText(this, "Copied. Paste it into the chat.", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WafflrService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
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
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    companion object {
        const val SAMPLE =
            "The morning train was late again, so Sarah decided to walk. It was only two miles, " +
            "and the rain had finally stopped. Along the way, she noticed things she'd never seen " +
            "from the carriage window: a bakery with a hand-painted sign, an old church clock stuck " +
            "at a quarter past three, and a fox trotting calmly across the park as if it owned the place."
    }
}
