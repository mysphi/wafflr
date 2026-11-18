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

    var results: String
        get() = sp.getString("results", "") ?: ""
        set(v) = sp.edit().putString("results", v).apply()
}
