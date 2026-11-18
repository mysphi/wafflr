package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 0 probe. Answers one question: when you select text in an app, does Android tell an
 * accessibility service what was selected? It reads nothing aloud; it only logs.
 *
 * Two signals are recorded:
 *  1. "selection event": the app sent TYPE_VIEW_TEXT_SELECTION_CHANGED.
 *  2. "scan after long-press": 0.8 s after a long-press, we search the screen for any text
 *     with an active selection, for apps that never send the event.
 */
class SelectionProbeService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastSelectionKey = ""

    override fun onServiceConnected() {
        SelectionLog.add("Probe switched on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: "?"
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionEvent(event, pkg)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                SelectionLog.add("[$pkg] long-press")
                handler.postDelayed({ scanForSelection(pkg) }, 800)
            }
        }
    }

    private fun onSelectionEvent(event: AccessibilityEvent, pkg: String) {
        val node = event.source
        val text = node?.text?.toString() ?: event.text.joinToString(" ")
        val start = node?.textSelectionStart ?: event.fromIndex
        val end = node?.textSelectionEnd ?: event.toIndex
        if (start < 0 || end <= start) return // caret moved, nothing selected
        val selected = text.safeSub(start, end)
        val key = "$pkg|$start|$end|${selected.hashCode()}"
        if (key == lastSelectionKey) return
        lastSelectionKey = key
        val editable = node?.isEditable == true
        SelectionLog.add("[$pkg] SELECTION EVENT (${if (editable) "editable field" else "read-only text"}): \"${selected.preview()}\"")
    }

    private fun scanForSelection(pkg: String) {
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Throwable) {
            listOfNotNull(rootInActiveWindow)
        }
        var found: String? = null
        for (root in roots) {
            found = findSelected(root, 0)
            if (found != null) break
        }
        if (found != null) {
            SelectionLog.add("[$pkg] scan after long-press FOUND selection: \"${found.preview()}\"")
        } else {
            SelectionLog.add("[$pkg] scan after long-press: no selected text visible to accessibility")
        }
    }

    private fun findSelected(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 60) return null
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        val t = node.text
        if (t != null && s >= 0 && e > s) return t.toString().safeSub(s, e)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findSelected(child, depth + 1)
            if (r != null) return r
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        SelectionLog.add("Probe switched off")
        super.onDestroy()
    }

    private fun String.safeSub(s: Int, e: Int): String =
        if (s in 0..length && e in s..length) substring(s, e) else ""

    private fun String.preview(): String =
        replace('\n', ' ').let { if (it.length > 80) it.take(80) + "…" else it }
}
