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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings: turning Wafflr on, the test report, and advanced options. */
class SettingsActivity : WafflrScreen() {
    private lateinit var serviceStatus: TextView
    private lateinit var shortcutWarning: LinearLayout
    private lateinit var bubbleSwitch: Switch
    private lateinit var results: TextView
    private lateinit var crash: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Wafflr settings"
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        // --- Turn on Wafflr ---------------------------------------------------------------------------
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

        // --- What Wafflr reads -------------------------------------------------------------------
        root.addView(heading("What Wafflr reads"))
        root.addView(body(
            "When reading the whole screen, Wafflr reads just the main text. Turn a switch on to " +
            "have that kind of text read too."
        ))
        readSwitch("Headings", "Titles and section headings", prefs.readHeadings) { prefs.readHeadings = it }
            .let(root::addView)
        readSwitch("Menus and buttons", "Toolbars, tabs, navigation bars and labels like Home or Search", prefs.readMenus) { prefs.readMenus = it }
            .let(root::addView)
        readSwitch("Picture descriptions", "Image descriptions, logos and photo credits", prefs.readPictures) { prefs.readPictures = it }
            .let(root::addView)
        readSwitch("Dates and article details", "Dates, Published or Updated times, comment counts, read times", prefs.readDetails) { prefs.readDetails = it }
            .let(root::addView)
        readSwitch("Text above the main story", "Short lines before the first full sentence, such as a byline or an email's banner", prefs.readLeadIn) { prefs.readLeadIn = it }
            .let(root::addView)

        // --- While reading -------------------------------------------------------------------------
        root.addView(heading("While reading"))
        readSwitch("Highlight what's being read", "A soft yellow box around the paragraph or area being read", prefs.highlight) { prefs.highlight = it }
            .let(root::addView)
        root.addView(body(
            "Headphone buttons and the lock screen can pause, skip and stop. For a quick start, add the " +
            "Wafflr tile to Quick Settings: pull down the shade, tap the pencil, and drag Wafflr in."
        ))

        // --- Test report ----------------------------------------------------------------------
        root.addView(heading("Test report"))
        crash = mono("")
        root.addView(crash)
        results = mono("")
        root.addView(results)
        root.addView(row(
            button("Copy report") { copyReport() },
            button("Clear") { prefs.results = ""; refreshResults() },
        ))
        root.addView(body("Advanced: CPU threads used for speech"))
        val threadOptions = listOf(1, 2, 4, 6)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, threadOptions.map { "$it threads" })
            setSelection(threadOptions.indexOf(prefs.threads).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.threads = threadOptions[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** A "read this too" switch with a one-line explanation under it. */
    private fun readSwitch(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        addView(Switch(this@SettingsActivity).apply {
            text = title
            textSize = 16f
            isChecked = on
            setOnCheckedChangeListener { _, value -> set(value) }
        })
        addView(TextView(this@SettingsActivity).apply {
            text = detail
            textSize = 13f
            alpha = 0.7f
        })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        serviceStatus.text = if (on) "✓ Wafflr is on" else "✗ Wafflr is off. Turn it on in Accessibility settings (look for Wafflr)."
        bubbleSwitch.isEnabled = on
        shortcutWarning.visibility = if (systemShortcutOn()) View.VISIBLE else View.GONE
        refreshResults()
        val lastCrash = CrashReport.lastCrash(this)
        crash.text = if (lastCrash.isEmpty()) "" else "Last time the app closed unexpectedly:\n$lastCrash\n(Included in the test report.)"
        crash.visibility = if (lastCrash.isEmpty()) View.GONE else View.VISIBLE
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
}
