package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
                bubble = FloatingBubble(this, app.prefs, app.reader, ::onBubbleTap, ::selectArea, ::openApp).also { it.show() }
            }
        } else {
            bubble?.hide()
            bubble = null
        }
    }

    /** The speaker reads the whole screen. (It deliberately never reads the clipboard.) */
    private fun onBubbleTap() = readScreen()

    private var selector: AreaSelector? = null
    private val areaReader by lazy { AreaReader(this) }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Lets you drag a box or draw a shape, then reads the text inside it. */
    fun selectArea(freehand: Boolean) {
        if (selector != null) return
        app.prefs.areaFreehand = freehand
        app.reader.stop()
        bubble?.setHidden(true)
        selector = AreaSelector(this, app.prefs) { area ->
            selector = null
            if (area == null) {
                bubble?.setHidden(false)
                return@AreaSelector
            }
            // Give the dimmed layer a moment to disappear before the screenshot.
            Handler(Looper.getMainLooper()).postDelayed({ readArea(area) }, 200)
        }.also { it.show() }
    }

    private fun readArea(area: SelectedArea) {
        areaReader.read(area) { result ->
            bubble?.setHidden(false)
            result.onSuccess { text ->
                if (text.isBlank()) {
                    Toast.makeText(this, "Wafflr couldn't find any text in that area.", Toast.LENGTH_SHORT).show()
                } else {
                    val p = app.prefs
                    app.reader.read(text, p.voice, p.speed, p.threads, "drawn area")
                }
            }.onFailure { e ->
                Toast.makeText(this, e.message ?: "Couldn't read that area.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun readScreen() {
        val p = app.prefs
        val found = try { ScreenText.read(this, p.readHeadings) } catch (_: Throwable) { null }
        if (found == null || found.text.isBlank()) {
            Toast.makeText(this, "Wafflr couldn't find any text to read here.", Toast.LENGTH_SHORT).show()
            return
        }
        app.reader.read(found.text, p.voice, p.speed, p.threads, found.summary())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {
        app.reader.stop()
    }

    override fun onDestroy() {
        app.prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        selector?.dismiss()
        selector = null
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
