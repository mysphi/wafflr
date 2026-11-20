package app.wafflr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Read with Wafflr" in the text-selection menu of apps that support it (e.g. Chrome), and in
 * the Share menu, which almost every app offers.
 *
 * Shows nothing of its own: it starts reading and closes straight away. The floating controls
 * open by themselves with pause, previous, next and stop.
 */
class ReadAloudActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent.action == Intent.ACTION_SEND
        val text = (if (shared) intent.getCharSequenceExtra(Intent.EXTRA_TEXT) else intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            ?.toString().orEmpty().trim().take(MAX_CHARS)
        val from = callingPackage ?: referrer?.host ?: "another app"
        val app = WafflrApp.instance
        val p = app.prefs

        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            // Nothing of Wafflr works over the lock screen.
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "Wafflr: no text was received.", Toast.LENGTH_SHORT).show()
        } else {
            val service = WafflrService.instance
            if (!shared && service != null) {
                // Selected on screen: find it there so it can be highlighted as it's read.
                service.readSelection(text, "selection in $from")
            } else {
                app.reader.read(text, p.voice, p.speed, p.threads, if (shared) "shared from $from" else "selection in $from", p.tuning)
            }
            if (WafflrService.instance == null || !p.bubbleEnabled) {
                // No floating controls to pause or stop with: say how to get them.
                Toast.makeText(this, "Reading. Turn on Wafflr's floating button in Settings to pause or stop.", Toast.LENGTH_LONG).show()
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        /** Longest text accepted from another app (about two hours of speech). */
        private const val MAX_CHARS = 200_000
    }
}
