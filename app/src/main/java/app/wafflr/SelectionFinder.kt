package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Finds text chosen with "Read with Wafflr" on screen. Android hands over only the words, not
 * where they are, so each paragraph of the selection is looked for in the app's text. A match
 * gives the paragraph's place (followed as the page scrolls) and its words (for the underline).
 *
 * Where the app splits a paragraph across pieces (links and bold text often do), the paragraph
 * can't be matched whole; its sentences are then passed on with no place yet, and the
 * highlighter finds each one in a screenshot.
 */
object SelectionFinder {
    private val spaces = Regex("\\s+")

    fun find(service: AccessibilityService, selection: String): List<Pair<String, Spot>> {
        val root = appRoot(service)
        val out = mutableListOf<Pair<String, Spot>>()
        for (para in selection.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            val hit = root?.let { match(it, para) }
            if (hit != null) {
                out += hit
            } else {
                // A new, empty place for each sentence: each is found on screen as it's read.
                for (s in TextSplitter.split(para)) out += s to Spot(Rect())
            }
        }
        return out
    }

    /** The node whose text holds [para], with the paragraph's own text as the app has it. */
    private fun match(root: AccessibilityNodeInfo, para: String): Pair<String, Spot>? {
        val want = para.replace(' ', ' ').replace(spaces, " ")
        val query = want.take(40).trim()
        if (query.isEmpty()) return null
        val nodes = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Throwable) { return null }
        for (node in nodes.filter { it.isVisibleToUser }) {
            val raw = node.text?.toString() ?: continue
            val plain = raw.replace(' ', ' ')
            var at = plain.indexOf(para.replace(' ', ' '))
            var text = if (at >= 0) raw.substring(at, at + para.length) else null
            if (at < 0) {
                // Spacing may differ (line breaks, double spaces): match ignoring it.
                val span = findIgnoringSpaces(plain, want) ?: continue
                at = span.first
                text = raw.substring(span.first, span.last + 1)
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return text!! to Spot(bounds, node, at)
        }
        return null
    }

    /** Where [want] (single-spaced) occurs in [text], allowing any run of spaces between words. */
    private fun findIgnoringSpaces(text: String, want: String): IntRange? {
        val pattern = want.split(' ').filter { it.isNotEmpty() }.joinToString("\\s+") { Regex.escape(it) }
        if (pattern.isEmpty()) return null
        return Regex(pattern).find(text)?.range
    }

    private fun appRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val own = service.packageName
        service.rootInActiveWindow?.let { if (it.packageName?.toString() != own) return it }
        return try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != own }
        } catch (_: Throwable) {
            null
        }
    }
}
