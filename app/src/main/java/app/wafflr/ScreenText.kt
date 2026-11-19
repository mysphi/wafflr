package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * What to read when reading the whole screen. Each flag is "read this too"; all are off by
 * default, so Wafflr reads just the main text. Set in Settings → What Wafflr reads.
 */
data class ReadOptions(
    val headings: Boolean = false,
    /** Menus, toolbars, buttons, tabs, navigation bars and short tap labels like "Home". */
    val menus: Boolean = false,
    /** Picture descriptions and credits ("Image source, Getty Images", "... logo"). */
    val pictures: Boolean = false,
    /** Dates, "Published", "Updated 3 hours ago", comment counts, read times. */
    val details: Boolean = false,
    /** Short lines above the first full sentence, such as a byline or an email banner. */
    val leadIn: Boolean = false,
)

/** What Wafflr found on screen, and what it left out. */
class ScreenRead(
    val text: String,
    /** Pieces of text skipped as menus, toolbars, buttons, tabs or navigation. */
    val skippedControls: Int,
    val skippedHeadings: Int,
    /** Image labels, dates, bylines and similar small print. */
    val skippedDetails: Int,
    /** True when everything looked like menus, so Wafflr read it all rather than nothing. */
    val fellBack: Boolean,
) {
    fun summary(): String = buildString {
        append("whole screen")
        val bits = mutableListOf<String>()
        if (skippedControls > 0) bits += "skipped $skippedControls menu/button items"
        if (skippedHeadings > 0) bits += "skipped $skippedHeadings headings"
        if (skippedDetails > 0) bits += "skipped $skippedDetails images/dates/bylines"
        if (fellBack) bits += "nothing but menus found, read everything"
        if (bits.isNotEmpty()) append(" (").append(bits.joinToString(", ")).append(")")
    }
}

/**
 * Collects the text currently visible on screen from the accessibility tree, in reading order,
 * leaving out the parts that aren't the main text:
 *
 *  - controls: buttons, switches, text fields, tabs, chips;
 *  - menus, toolbars, app bars, navigation bars and drawers, found by their class, their view
 *    id (e.g. "toolbar", "bottom_nav") or, in Chrome and web views, their web role
 *    ("navigation", "banner", "menu", "toolbar", "contentinfo", ...);
 *  - short tappable labels such as "Home", "Reply" or "Share" (one to three words);
 *  - headings, unless the user turned "Read headings" on;
 *  - pictures' descriptions, then the small print [TextTidy] removes (dates, bylines, ...).
 *
 * If that leaves nothing at all, it reads everything instead, so the button never just fails.
 */
object ScreenText {
    private val controlClasses = setOf(
        "android.widget.Button", "android.widget.ImageButton", "android.widget.CompoundButton",
        "android.widget.CheckBox", "android.widget.RadioButton", "android.widget.Switch",
        "android.widget.ToggleButton", "android.widget.EditText", "android.widget.AutoCompleteTextView",
        "android.widget.Spinner", "android.widget.SeekBar", "android.widget.TabWidget",
        "android.app.ActionBar\$Tab",
    )

    /** Parts of a class name that mean "this whole area is chrome, not content". */
    private val chromeClassHints = listOf(
        "Toolbar", "ActionBar", "ActionMenu", "MenuView", "MenuItem", "NavigationBar", "BottomNavigation",
        "NavigationRail", "NavigationView", "TabLayout", "TabView", "TabWidget",
        "Chip", "FloatingActionButton", "Snackbar", "SearchView", "MaterialButton",
    )

    /** Words in a view id that mean the same, e.g. "com.app:id/bottom_nav" or "toolbar_title". */
    private val chromeIdWords = setOf(
        "toolbar", "actionbar", "appbar", "menu", "nav", "navigation", "navbar", "bottomnav",
        "bottombar", "tabs", "tablayout", "tabbar", "fab", "snackbar", "searchbar", "drawer", "breadcrumb",
        "breadcrumbs",
    )

    /** Id words for containers that usually hold the whole page, e.g. "nav_host_fragment". */
    private val containerIdWords = setOf("host", "content", "container", "layout", "fragment", "root", "main", "pager", "scroll")

    /** Web roles (Chrome and web views) for page furniture rather than the article. */
    private val chromeWebRoles = setOf(
        "navigation", "banner", "contentinfo", "complementary", "search", "menu", "menubar",
        "menuitem", "menuitemcheckbox", "menuitemradio", "toolbar", "tablist", "tab", "button",
        "combobox", "textbox", "searchbox", "listbox", "option", "checkbox", "radio", "switch",
        "slider", "spinbutton", "tree", "treeitem",
    )
    private val headingWebRoles = setOf("heading")
    private const val NO_TAP = 1000

    fun read(service: AccessibilityService, options: ReadOptions): ScreenRead {
        val root = targetRoot(service) ?: return ScreenRead("", 0, 0, 0, false)
        val rootBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val filtered = Collector(filter = true, options = options, page = rootBounds)
            .also { it.collect(root, 0, null, NO_TAP) }
        val tidied = TextTidy.tidy(filtered.parts, options)
        val text = join(tidied.lines)
        if (text.isNotBlank()) {
            return ScreenRead(text, filtered.skippedControls, filtered.skippedHeadings, filtered.skippedImages + tidied.skipped, false)
        }
        val everything = Collector(filter = false, options = options, page = rootBounds).also { it.collect(root, 0, null, NO_TAP) }
        return ScreenRead(join(everything.parts), 0, 0, 0, everything.parts.isNotEmpty())
    }

    /** Drops exact repeats (e.g. a title shown twice) while keeping order. */
    private fun join(parts: List<String>): String {
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

    private class Collector(val filter: Boolean, val options: ReadOptions, val page: android.graphics.Rect) {
        private val skipMenus = filter && !options.menus
        private val skipPictures = filter && !options.pictures
        private val skipHeadings = filter && !options.headings
        val parts = mutableListOf<String>()
        var skippedControls = 0
        var skippedHeadings = 0
        var skippedImages = 0

        /**
         * [parentText]: nearest ancestor's text, so links inside a paragraph aren't read twice.
         * [tapGap]: levels up to the nearest tappable ancestor. A label one or two levels inside
         * a tappable item (the text under a navigation bar icon) counts as part of it; deeper
         * text, like a whole email inside a tappable panel, doesn't.
         */
        fun collect(node: AccessibilityNodeInfo, depth: Int, parentText: String?, tapGap: Int) {
            val inTap = tapGap <= 2
            if (depth > 80 || !node.isVisibleToUser) return
            if (node.isEditable || node.isPassword) return // never read what people type
            if (skipMenus && isChrome(node, page.height())) {
                skippedControls += countText(node, 0)
                return
            }
            val text = node.text?.toString()?.trim()
            var context = parentText
            if (!text.isNullOrEmpty()) {
                val repeat = parentText != null && parentText.contains(text)
                when {
                    repeat -> {}
                    skipPictures && isImage(node) -> skippedImages++
                    skipHeadings && isHeading(node) -> skippedHeadings++
                    skipMenus && isShortTapLabel(node, text, inTap) -> skippedControls++
                    skipMenus && isBottomBarLabel(node, text, page) -> skippedControls++
                    else -> parts += text
                }
                context = text
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, depth + 1, context, if (node.isClickable) 1 else tapGap + 1)
            }
        }

        /** How many pieces of text a skipped area held, for the test report. */
        private fun countText(node: AccessibilityNodeInfo, depth: Int): Int {
            if (depth > 40) return 0
            var n = if (node.text.isNullOrBlank()) 0 else 1
            for (i in 0 until node.childCount) node.getChild(i)?.let { n += countText(it, depth + 1) }
            return n
        }
    }

    /** True for controls and for whole menu / toolbar / navigation areas. */
    private fun isChrome(node: AccessibilityNodeInfo, pageHeight: Int): Boolean {
        val cls = node.className?.toString() ?: ""
        if (cls in controlClasses) return true
        if (chromeClassHints.any { cls.contains(it) }) return true
        if (webRole(node) in chromeWebRoles) return true
        if (roleDescription(node) in controlRoleDescriptions) return true
        // Ids are only a hint: a bar or menu is small, so anything taller than about a third
        // of the page (e.g. "nav_host_fragment", which holds the whole page) is never skipped.
        val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
        if (id != null) {
            val words = id.split('_', '-', '.')
            val looksLikeBar = words.any { it in chromeIdWords } || id.replace("_", "") in chromeIdWords
            if (looksLikeBar && words.none { it in containerIdWords }) {
                val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                if (pageHeight <= 0 || r.height() < pageHeight / 3) return true
            }
        }
        return false
    }

    /** Pictures: their "text" is a description (alt text), not part of what you're reading. */
    private fun isImage(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        if (cls == "android.widget.Image" || cls == "android.widget.ImageView") return true
        return webRole(node) in setOf("image", "img", "graphics-document", "graphics-symbol", "figure-caption-source")
    }

    private fun isHeading(node: AccessibilityNodeInfo): Boolean =
        node.isHeading || webRole(node) in headingWebRoles

    /**
     * A one-to-three-word label you tap, like "Home", "Reply" or "See all". Longer tappable text
     * (a headline that links to a story) is kept.
     */
    private fun isShortTapLabel(node: AccessibilityNodeInfo, text: String, inTap: Boolean): Boolean {
        if (!(node.isClickable || node.isLongClickable || inTap)) return false
        if (webRole(node) == "link" && node.parent?.text != null) return false // link inside a sentence
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return words <= 3 && !text.endsWith(".")
    }

    /**
     * A one-to-three-word label along the bottom edge of the page: almost always a navigation
     * bar ("Home", "My News", "Search") even when the app doesn't mark it as one.
     */
    private fun isBottomBarLabel(node: AccessibilityNodeInfo, text: String, page: android.graphics.Rect): Boolean {
        if (page.height() <= 0) return false
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words > 3 || text.endsWith(".")) return false
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return r.top >= page.top + page.height() * 0.85
    }

    /** What apps built with Jetpack Compose and others call the control, e.g. "Tab", "Button". */
    private val controlRoleDescriptions = setOf(
        "tab", "button", "switch", "checkbox", "check box", "radio button", "toggle button",
        "dropdown list", "drop down list", "menu", "menu item", "navigation bar", "toolbar",
    )

    private fun roleDescription(node: AccessibilityNodeInfo): String? = try {
        node.extras?.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()?.lowercase()
    } catch (_: Throwable) {
        null
    }

    /** Chrome and WebView put the element's web role in the node's extras. */
    private fun webRole(node: AccessibilityNodeInfo): String? = try {
        node.extras?.getCharSequence("AccessibilityNodeInfo.chromeRole")?.toString()?.lowercase()
    } catch (_: Throwable) {
        null
    }
}
