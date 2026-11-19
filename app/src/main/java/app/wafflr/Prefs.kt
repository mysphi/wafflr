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

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    /** Which screen edge the floating button sits on, and how far down (pixels). */
    var bubbleRight: Boolean
        get() = sp.getBoolean("bubble_right", true)
        set(v) = sp.edit().putBoolean("bubble_right", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    /** Timestamp of the copied text Wafflr last read, so the same copy isn't read twice. */
    var lastClipStamp: Long
        get() = sp.getLong("last_clip", 0L)
        set(v) = sp.edit().putLong("last_clip", v).apply()

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
