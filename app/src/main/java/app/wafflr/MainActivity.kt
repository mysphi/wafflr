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

/** Main screen: how to use Wafflr, voices, and a place to try them. Set-up lives in [SettingsActivity]. */
class MainActivity : WafflrScreen(), ReaderListener {
    private lateinit var offWarning: LinearLayout
    private lateinit var voiceSpinner: Spinner
    private lateinit var installedList: LinearLayout
    private lateinit var availableList: LinearLayout
    private var spinnerVoices: List<Voice> = emptyList()
    /** The voice being downloaded now (one at a time), and its latest progress line. */
    private var downloading: VoicePack? = null
    private var downloadStatus = ""
    private lateinit var input: EditText
    private lateinit var speedLabel: TextView
    private lateinit var status: TextView
    private lateinit var playButton: Button
    private val reader get() = app.reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        // The top bar already shows "Wafflr" and the settings cog. Only if there is no top bar,
        // show a cog here instead.
        if (actionBar == null) root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            run {
                addView(android.widget.ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_settings)
                    background = null
                    contentDescription = "Settings"
                    setOnClickListener { openSettings() }
                }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
            }
        })
        root.addView(body("Reads the text on your screen aloud, offline."))

        // Shown only while the accessibility switch is off.
        offWarning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body("⚠ Wafflr is off, so the floating controls won't appear. Turn it on in Settings."))
            addView(button("Open Settings") { openSettings() })
        }
        root.addView(offWarning)

        root.addView(heading("Using Wafflr"))
        root.addView(body(
            "Floating controls: tap the Wafflr logo to open them; drag it to move; long-press it to open this app. " +
            "The speaker reads the whole screen, and becomes pause/resume while reading. " +
            "Beside it: draw a box or a shape around just the text you want; while reading, previous, next and stop.\n" +
            "Or choose Share → Read with Wafflr (some apps also show 'Read with Wafflr' straight in the selection menu)."
        ))

        // --- Voice ----------------------------------------------------------------------------
        root.addView(heading("Voice"))
        root.addView(body("Choose from the voices on this phone."))
        voiceSpinner = Spinner(this).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val chosen = spinnerVoices.getOrNull(pos) ?: return
                    if (chosen != prefs.voice) prefs.voice = chosen
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(voiceSpinner)
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

        // Pitch: 0.70 .. 1.30 in 0.05 steps.
        root.addView(slider(
            label = { "Pitch: " + when { it < 0.99f -> "lower (%.2f)".format(it); it > 1.01f -> "higher (%.2f)".format(it); else -> "normal" } },
            min = 0.70f, step = 0.05f, steps = 12, value = prefs.pitch,
        ) { prefs.pitch = it })
        // Expression: 0.20 (flat, steady) .. 1.00 (lively) in 0.05 steps; Piper's default is 0.667.
        root.addView(slider(
            label = { "Expression: " + when { it < 0.55f -> "steadier"; it > 0.8f -> "livelier"; else -> "normal" } + " (%.2f)".format(it) },
            min = 0.20f, step = 0.05f, steps = 16, value = prefs.expression,
        ) { prefs.expression = it })
        root.addView(button("Reset pitch and expression") {
            prefs.pitch = 1.0f
            prefs.expression = Tuning.DEFAULT_EXPRESSION
            recreate()
        })

        // --- Downloaded voices ------------------------------------------------------------------
        root.addView(heading("Downloaded voices"))
        root.addView(body("These work offline. Remove one to free up space. The last voice can't be removed."))
        installedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(installedList)

        // --- Download more ----------------------------------------------------------------------
        root.addView(heading("Download more voices"))
        root.addView(body("Each voice is about 65 MB. Wi-Fi is best."))
        availableList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(availableList)

        // --- Try it ---------------------------------------------------------------------------
        root.addView(heading("Try it"))
        input = EditText(this).apply { setText(SAMPLE); minLines = 4; textSize = 15f }
        root.addView(input)
        playButton = button("Read") { onPlay() }
        root.addView(row(playButton, button("Stop") { reader.stop() }))
        status = body("")
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }


    override fun onResume() {
        super.onResume()
        reader.addListener(this)
        offWarning.visibility = if (isServiceEnabled()) View.GONE else View.VISIBLE
        refreshPack()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "Settings").apply {
            setIcon(R.drawable.ic_settings)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) { openSettings(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        reader.removeListener(this)
        super.onPause()
    }

    private fun onPlay() {
        when (reader.status.state) {
            ReaderState.PLAYING, ReaderState.PAUSED -> reader.togglePause()
            ReaderState.PREPARING -> {}
            ReaderState.IDLE -> reader.read(input.text.toString(), prefs.voice, prefs.speed, prefs.threads, "Try it", prefs.tuning)
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
        refreshPack()
    }

    override fun onError(message: String) {
        status.text = "Error: $message"
        refreshPack()
    }

    /** Rebuilds the list of downloaded voices, each with a Remove button. */
    private fun refreshInstalled() {
        installedList.removeAllViews()
        val installed = app.voices.installedPacks()
        if (installed.isEmpty()) {
            installedList.addView(body("None yet. The built-in voice is being set up."))
            return
        }
        for (pack in installed) {
            val mb = app.voices.sizeOf(pack) / 1_000_000
            val label = body("${VoiceCatalog.title(pack)}\n$mb MB")
            val remove = button("Remove") { confirmRemove(pack) }.apply {
                isEnabled = installed.size > 1
            }
            installedList.addView(voiceRow(label, remove))
        }
    }

    private fun confirmRemove(pack: VoicePack) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove ${VoiceCatalog.title(pack)}?")
            .setMessage("This frees up the space it uses. You can download it again later.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removePack(pack) }
            .show()
    }

    private fun removePack(pack: VoicePack) {
        val others = app.voices.installedPacks().filter { it != pack }
        if (others.isEmpty()) {
            Toast.makeText(this, "Wafflr needs at least one voice, so the last one can't be removed.", Toast.LENGTH_LONG).show()
            return
        }
        // Stop anything using it, free it, delete the files.
        if (reader.status.state != ReaderState.IDLE) reader.stop()
        app.models.release(pack)
        app.voices.delete(pack)
        // If it was the chosen voice, switch to one that's still on the phone.
        if (prefs.voice.pack == pack) {
            val next = VoiceCatalog.voices.first { it.pack == others.first() }
            prefs.voice = next
            Toast.makeText(this, "Switched to ${next.label}.", Toast.LENGTH_SHORT).show()
        }
        refreshPack()
    }

    /** Refreshes the voice picker and both voice lists. */
    private fun refreshPack() {
        val installed = app.voices.installedPacks()
        // The chosen voice must always be one that's on the phone.
        if (installed.isNotEmpty() && prefs.voice.pack !in installed) {
            prefs.voice = VoiceCatalog.voices.first { it.pack in installed }
        }
        spinnerVoices = VoiceCatalog.voices.filter { it.pack in installed }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spinnerVoices.map { it.label })
        voiceSpinner.setSelection(spinnerVoices.indexOf(prefs.voice).coerceAtLeast(0))
        refreshInstalled()
        refreshAvailable()
    }

    /** Voices not on the phone yet, each with a Download button. */
    private fun refreshAvailable() {
        availableList.removeAllViews()
        val available = VoiceCatalog.packs.filter { !app.voices.isInstalled(it) }
        if (available.isEmpty()) {
            availableList.addView(body("You have every voice."))
            return
        }
        for (pack in available) {
            val busy = downloading == pack
            val text = VoiceCatalog.title(pack) + if (busy) "\n$downloadStatus" else ""
            val label = body(text)
            val get = button(if (busy) "…" else "Download") { download(pack) }.apply {
                isEnabled = downloading == null
            }
            availableList.addView(voiceRow(label, get))
        }
    }

    private fun download(pack: VoicePack) {
        if (downloading != null) return
        downloading = pack
        downloadStatus = "Starting download…"
        refreshAvailable()
        Thread({
            val error = try {
                app.voices.install(pack) { msg ->
                    runOnUiThread { downloadStatus = msg; refreshAvailable() }
                }
                null
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            runOnUiThread {
                downloading = null
                refreshPack()
                Toast.makeText(
                    this,
                    if (error == null) "${VoiceCatalog.title(pack)} is ready." else "Download failed: $error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, "voice-download").start()
    }

    private fun voiceRow(label: View, action: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }


    /** A labelled slider for a float setting: [steps] steps of [step] from [min]. */
    private fun slider(label: (Float) -> String, min: Float, step: Float, steps: Int, value: Float, set: (Float) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val text = body(label(value))
            addView(text)
            addView(SeekBar(this@MainActivity).apply {
                max = steps
                progress = Math.round((value - min) / step).coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + p * step
                        if (fromUser) set(v)
                        text.text = label(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }

    private fun updateSpeedLabel() {
        speedLabel.text = "Speed: %.1f×".format(prefs.speed)
    }



    companion object {
        private const val MENU_SETTINGS = 1
        const val SAMPLE =
            "The morning train was late again, so Sarah decided to walk. It was only two miles, " +
            "and the rain had finally stopped. Along the way, she noticed things she'd never seen " +
            "from the carriage window: a bakery with a hand-painted sign, an old church clock stuck " +
            "at a quarter past three, and a fox trotting calmly across the park as if it owned the place."
    }
}
