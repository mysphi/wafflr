package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Collects the text currently visible on screen from the accessibility tree, in reading order.
 *
 * Phase 1 keeps the filtering simple: it skips obvious controls (buttons, switches, tabs, text
 * fields). Smarter skipping of menus, toolbars and headings comes in Phase 3.
 */
object ScreenText {
    private val controlClasses = setOf(
        "android.widget.Button", "android.widget.ImageButton", "android.widget.CompoundButton",
        "android.widget.CheckBox", "android.widget.RadioButton", "android.widget.Switch",
        "android.widget.ToggleButton", "android.widget.EditText", "android.widget.Spinner",
        "android.widget.SeekBar", "android.widget.TabWidget", "android.app.ActionBar\$Tab",
        "com.google.android.material.button.MaterialButton",
        "com.google.android.material.chip.Chip",
        "com.google.android.material.tabs.TabLayout\$TabView",
    )

    fun read(service: AccessibilityService): String {
        val root = targetRoot(service) ?: return ""
        val parts = mutableListOf<String>()
        collect(root, parts, 0, null)
        // Drop exact repeats (e.g. a title shown twice) while keeping order.
        val seen = HashSet<String>()
        return parts.filter { seen.add(it) }.joinToString("\n")
    }

    /** The app window the user is looking at, never Wafflr's own floating button. */
    private fun targetRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val own = service.packageName
        service.rootInActiveWindow?.let { if (it.packageName?.toString() != own) return it }
        return try {
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != own }
        } catch (_: Throwable) {
            null
        }
    }

    /** [parentText]: nearest ancestor's text, so links inside a paragraph aren't read twice. */
    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, parentText: String?) {
        if (depth > 80 || !node.isVisibleToUser) return
        val cls = node.className?.toString() ?: ""
        if (cls in controlClasses || node.isEditable || node.isPassword) return
        val text = node.text?.toString()?.trim()
        var context = parentText
        if (!text.isNullOrEmpty()) {
            if (parentText == null || !parentText.contains(text)) out += text
            context = text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, context)
        }
    }
}
