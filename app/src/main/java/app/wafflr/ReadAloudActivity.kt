package app.wafflr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Appears as "Read with Wafflr" in the text-selection menu of other apps (Android's PROCESS_TEXT).
 * This is the reliable way to read selected text, even in apps that hide selections from
 * accessibility services.
 */
class ReadAloudActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
        val from = callingPackage ?: referrer?.host ?: "unknown app"
        SelectionLog.add("[$from] SELECTION MENU 'Read with Wafflr': ${text.length} chars received")

        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        val stop = Button(this).apply {
            setText("Stop")
            setOnClickListener {
                WafflrApp.instance.speech.stop()
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad * 2, pad * 2, pad * 2, pad)
            addView(status)
            addView(stop)
        })
        setFinishOnTouchOutside(false)

        if (text.isEmpty()) {
            status.text = "No text was received."
            return
        }
        val prefs = Prefs(this)
        val voice = prefs.voice
        status.text = "Reading with ${voice.label}…"
        WafflrApp.instance.speech.speak(text, voice, prefs.speed, prefs.threads, object : SpeechListener {
            override fun onStatus(text: String) = runOnUiThread { status.text = text }
            override fun onDone(result: SpeechResult) = runOnUiThread {
                prefs.results = "From selection menu ($from):\n" + result.summary() + "\n\n" + prefs.results
                if (!isFinishing) finish()
            }
            override fun onError(message: String) = runOnUiThread { status.text = "Error: $message" }
        })
    }
}
