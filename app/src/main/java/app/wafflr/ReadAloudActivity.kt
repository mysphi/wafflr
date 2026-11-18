package app.wafflr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Read with Wafflr" in the text-selection menu of apps that support it (e.g. Chrome).
 * Shows a small panel with Pause and Stop; closing it keeps reading, and the floating button
 * keeps the controls.
 */
class ReadAloudActivity : Activity(), ReaderListener {
    private lateinit var status: TextView
    private lateinit var pause: Button
    private val reader get() = WafflrApp.instance.reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
        val from = callingPackage ?: referrer?.host ?: "another app"

        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        pause = Button(this).apply { setText("Pause"); setOnClickListener { reader.togglePause() } }
        val stop = Button(this).apply {
            setText("Stop")
            setOnClickListener { reader.stop(); finish() }
        }
        val close = Button(this).apply { setText("Hide"); setOnClickListener { finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad * 2, pad * 2, pad * 2, pad)
            addView(status)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pause); addView(stop); addView(close)
            })
        })

        if (text.isEmpty()) {
            status.text = "No text was received."
            return
        }
        val p = WafflrApp.instance.prefs
        reader.read(text, p.voice, p.speed, p.threads, "selection in $from")
    }

    override fun onStart() { super.onStart(); reader.addListener(this) }
    override fun onStop() { reader.removeListener(this); super.onStop() }

    override fun onStatus(status: ReaderStatus) {
        this.status.text = when (status.state) {
            ReaderState.PREPARING -> status.message.ifEmpty { "Getting ready…" }
            ReaderState.PLAYING -> "Reading ${status.index + 1} of ${status.total}"
            ReaderState.PAUSED -> "Paused"
            ReaderState.IDLE -> "Done"
        }
        pause.text = if (status.state == ReaderState.PAUSED) "Resume" else "Pause"
    }

    override fun onFinished(result: SpeechResult) { if (!isFinishing) finish() }

    override fun onError(message: String) { status.text = "Error: $message" }
}
