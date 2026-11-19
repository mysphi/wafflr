package app.wafflr

import android.app.Activity
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Opened (invisibly) when the floating button is tapped. If you copied some text in the last
 * couple of minutes, Wafflr reads that; otherwise it reads the whole screen.
 *
 * Android only lets the app in front read the clipboard, which is why this exists.
 */
class ClipboardReadActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val copied = freshCopiedText()
        finish()
        overridePendingTransition(0, 0)
        val app = WafflrApp.instance
        if (copied != null) {
            val p = app.prefs
            app.reader.read(copied, p.voice, p.speed, p.threads, "copied text")
        } else {
            // Wait until this invisible screen has gone so the app underneath is read.
            Handler(Looper.getMainLooper()).postDelayed({ WafflrService.instance?.readScreen() }, 250)
        }
    }

    private fun freshCopiedText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = try { cm.primaryClip } catch (_: Throwable) { null } ?: return null
        if (clip.itemCount == 0) return null
        val stamp = clip.description?.timestamp ?: 0L
        val prefs = WafflrApp.instance.prefs
        if (stamp == 0L || stamp == prefs.lastClipStamp) return null // nothing new since last time
        val ageWall = System.currentTimeMillis() - stamp
        val ageBoot = SystemClock.elapsedRealtime() - stamp
        val fresh = ageWall in 0..FRESH_MS || ageBoot in 0..FRESH_MS
        if (!fresh) return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) return null
        prefs.lastClipStamp = stamp
        return text
    }

    companion object {
        private const val FRESH_MS = 2 * 60 * 1000L
    }
}
