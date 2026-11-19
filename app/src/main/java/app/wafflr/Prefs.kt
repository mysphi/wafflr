package app.wafflr

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wafflr", Context.MODE_PRIVATE)

    var voice: Voice
        get() = VoiceCatalog.byId(sp.getString("voice", null))
        set(v) = sp.edit().putString("voice", v.id).apply()

    var speed: Float
        get() = sp.getFloat("speed", 1.0f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    var threads: Int
        get() = sp.getInt("threads", 4)
        set(v) = sp.edit().putInt("threads", v).apply()

    /** Read headings aloud when reading the whole screen. Off by default (Gareth's choice). */
    var readHeadings: Boolean
        get() = sp.getBoolean("read_headings", false)
        set(v) = sp.edit().putBoolean("read_headings", v).apply()

    /** "Read this too" switches for whole-screen reading; all off by default. */
    var readMenus: Boolean
        get() = sp.getBoolean("read_menus", false)
        set(v) = sp.edit().putBoolean("read_menus", v).apply()
    var readPictures: Boolean
        get() = sp.getBoolean("read_pictures", false)
        set(v) = sp.edit().putBoolean("read_pictures", v).apply()
    var readDetails: Boolean
        get() = sp.getBoolean("read_details", false)
        set(v) = sp.edit().putBoolean("read_details", v).apply()
    var readLeadIn: Boolean
        get() = sp.getBoolean("read_lead_in", false)
        set(v) = sp.edit().putBoolean("read_lead_in", v).apply()

    val readOptions: ReadOptions
        get() = ReadOptions(readHeadings, readMenus, readPictures, readDetails, readLeadIn)

    var pitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    var expression: Float
        get() = sp.getFloat("expression", Tuning.DEFAULT_EXPRESSION)
        set(v) = sp.edit().putFloat("expression", v).apply()

    val tuning: Tuning get() = Tuning(pitch, expression)

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    /** Which screen edge the floating button sits on, and how far down (pixels). */
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubble_right", true)
        set(v) = sp.edit().putBoolean("bubble_right", v).apply()

    /** Distance of the floating logo from the top of the screen, in pixels (-1 = default). */
    var bubbleY: Int
        get() = sp.getInt("bubble_top", -1)
        set(v) = sp.edit().putInt("bubble_top", v).apply()

    /** Area reading: freehand shape (true) or box (false). */
    var areaFreehand: Boolean
        get() = sp.getBoolean("area_freehand", false)
        set(v) = sp.edit().putBoolean("area_freehand", v).apply()

    fun addResult(text: String) {
        // Keep the newest 30 entries.
        val kept = results.split("\n\n").filter { it.isNotBlank() }.take(29)
        results = (listOf(text) + kept).joinToString("\n\n")
    }

    val prefs get() = sp

    var results: String
        get() = sp.getString("results", "") ?: ""
        set(v) = sp.edit().putString("results", v).apply()
}
