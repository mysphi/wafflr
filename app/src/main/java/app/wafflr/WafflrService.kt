package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
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
    private var highlighter: Highlighter? = null
    private val app get() = WafflrApp.instance
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble") updateBubble()
    }

    override fun onServiceConnected() {
        instance = this
        app.prefs.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        updateBubble()
        highlighter = Highlighter(this, app.prefs, app.reader) { rect, done -> areaReader.readWords(rect, done) }.also { app.reader.addListener(it) }
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
                    app.reader.read(text, p.voice, p.speed, p.threads, "drawn area", p.tuning, area = area.bounds)
                }
            }.onFailure { e ->
                Toast.makeText(this, e.message ?: "Couldn't read that area.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun readScreen() {
        val p = app.prefs
        val found = try { ScreenText.read(this, p.readOptions) } catch (_: Throwable) { null }
        if (found != null && found.hasSentence) {
            app.reader.readPieces(found.pieces, p.voice, p.speed, p.threads, found.summary(), p.tuning)
            return
        }
        // The app shared no real sentences (some apps keep their article text out of reach of
        // accessibility services). Read a picture of the screen instead, skipping the places
        // where the scan found menus and bars, and the status bar.
        readScreenPicture(found)
    }

    private fun readScreenPicture(found: ScreenRead?) {
        val p = app.prefs
        bubble?.setHidden(true) // keep the floating controls out of the picture
        Handler(Looper.getMainLooper()).postDelayed({
            areaReader.readScreenBlocks { result ->
                bubble?.setHidden(false)
                val blocks = result.getOrNull().orEmpty()
                val statusBar = try {
                    getSystemService(android.view.WindowManager::class.java)!!.currentWindowMetrics.windowInsets
                        .getInsets(android.view.WindowInsets.Type.statusBars()).top
                } catch (_: Throwable) { 0 }
                val skipAreas = found?.chromeRects.orEmpty()
                // The scan found no sentences, so whatever it did find were labels ("Home",
                // "Search"): skip those words in the picture too.
                val labels = found?.pieces.orEmpty().map { it.first.lowercase() }.toSet()
                val screenH = resources.displayMetrics.heightPixels
                val kept = blocks.filter { (text, r) ->
                    val short = text.split(Regex("\\s+")).size <= 5 && !text.trimEnd().endsWith(".")
                    r.centerY() > statusBar &&
                        skipAreas.none { it.contains(r.centerX(), r.centerY()) } &&
                        text.lowercase() !in labels &&
                        // short labels in a bar along the top or bottom edge
                        !(short && (r.centerY() > screenH * 0.88 || r.centerY() < screenH * 0.12))
                }
                val tidied = TextTidy.tidy(kept.map { it.first }, p.readOptions)
                val pieces = tidied.kept.map { kept[it].first to Spot(kept[it].second) }
                when {
                    pieces.isNotEmpty() ->
                        app.reader.readPieces(pieces, p.voice, p.speed, p.threads, "whole screen, from a picture (app shared no text)", p.tuning)
                    found != null && found.pieces.isNotEmpty() ->
                        app.reader.readPieces(found.pieces, p.voice, p.speed, p.threads, found.summary(), p.tuning)
                    else ->
                        Toast.makeText(this, result.exceptionOrNull()?.message ?: "Wafflr couldn't find any text to read here.", Toast.LENGTH_SHORT).show()
                }
            }
        }, 150)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The page scrolled or changed under the highlight: it would point at the wrong text.
        if (event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> highlighter?.let { h ->
                if (!h.active) return
                // Which part of the screen scrolled, so a carousel elsewhere doesn't move the box.
                val area = try {
                    event.source?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
                } catch (_: Throwable) { null }
                h.pageMoved(event.scrollDeltaX, event.scrollDeltaY, area)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> highlighter?.pageMoved()
        }
    }

    override fun onInterrupt() {
        app.reader.stop()
    }

    override fun onDestroy() {
        app.prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        selector?.dismiss()
        selector = null
        bubble?.hide()
        bubble = null
        highlighter?.let { app.reader.removeListener(it); it.hide() }
        highlighter = null
        app.reader.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: WafflrService? = null
            private set
    }
}
