package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Wafflr's accessibility service. It draws the floating button over other apps and reads the
 * text on screen. It does not watch what you type or send anything off the phone.
 */
class WafflrService : AccessibilityService() {
    private var bubble: FloatingBubble? = null
    private val app get() = WafflrApp.instance
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble") updateBubble()
    }

    override fun onServiceConnected() {
        instance = this
        app.prefs.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        updateBubble()
    }

    private fun updateBubble() {
        if (app.prefs.bubbleEnabled) {
            if (bubble == null) {
                bubble = FloatingBubble(this, app.prefs, app.reader) { onBubbleTap() }.also { it.show() }
            }
        } else {
            bubble?.hide()
            bubble = null
        }
    }

    /** Reads text you just copied, or else the whole screen. */
    private fun onBubbleTap() {
        try {
            startActivity(
                Intent(this, ClipboardReadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (_: Throwable) {
            readScreen() // couldn't check the clipboard; fall back
        }
    }

    fun readScreen() {
        val text = try { ScreenText.read(this) } catch (_: Throwable) { "" }
        if (text.isBlank()) {
            Toast.makeText(this, "Wafflr couldn't find any text to read here.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = app.prefs
        app.reader.read(text, p.voice, p.speed, p.threads, "whole screen")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {
        app.reader.stop()
    }

    override fun onDestroy() {
        app.prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        bubble?.hide()
        bubble = null
        app.reader.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: WafflrService? = null
            private set
    }
}
