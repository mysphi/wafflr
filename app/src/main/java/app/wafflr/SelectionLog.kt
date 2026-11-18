package app.wafflr

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records what the selection probe saw, so we can tell which apps report selected text. */
object SelectionLog {
    private lateinit var file: File
    private val listeners = mutableSetOf<() -> Unit>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)

    fun init(context: Context) {
        file = File(context.filesDir, "selection-log.txt")
    }

    @Synchronized
    fun add(line: String) {
        file.appendText("${time.format(Date())}  $line\n")
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun read(): String = if (file.exists()) file.readText() else ""

    @Synchronized
    fun clear() {
        file.delete()
        listeners.toList().forEach { it() }
    }

    @Synchronized fun addListener(l: () -> Unit) { listeners += l }
    @Synchronized fun removeListener(l: () -> Unit) { listeners -= l }
}
