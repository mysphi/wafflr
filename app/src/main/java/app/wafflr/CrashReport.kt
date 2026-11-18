package app.wafflr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the app last closed unexpectedly, so a crash on the phone can be copied into the
 * chat. Java/Kotlin crashes are saved with their stack trace; crashes inside the voice engine's
 * native code are read back from Android's exit records on the next launch.
 */
object CrashReport {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file.writeText("${stamp(System.currentTimeMillis())} crash on thread ${thread.name}\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Text describing the most recent unexpected exit, or empty if the last exit was normal. */
    fun lastCrash(context: Context): String {
        val saved = if (file.exists()) file.readText() else ""
        val am = context.getSystemService(ActivityManager::class.java) ?: return saved
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return saved
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "app crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in native code (voice engine)"
            ApplicationExitInfo.REASON_ANR -> "app stopped responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for low memory"
            else -> return saved
        }
        var line = "${stamp(info.timestamp)} last exit: $kind. ${info.description ?: ""}".trim()
        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            val trace = nativeTrace(info)
            if (trace.isNotEmpty()) line += "\n$trace"
        }
        return if (saved.isNotEmpty() && info.reason == ApplicationExitInfo.REASON_CRASH) "$line\n$saved" else line
    }

    /**
     * Android keeps a binary "tombstone" for native crashes. We can't parse it fully here, but
     * its readable strings include the crash signal, abort message and function names.
     */
    private fun nativeTrace(info: ApplicationExitInfo): String = try {
        val bytes = info.traceInputStream?.use { it.readBytes() } ?: ByteArray(0)
        val runs = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126) sb.append(c.toChar()) else {
                if (sb.length >= 6) runs += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= 6) runs += sb.toString()
        val keys = listOf("SIG", "abort", "Abort", "sherpa", "espeak", "piper", "onnx", "Ort", "kokoro", "Kokoro", "terminat", "exception", "Exception", "fault", "std::")
        runs.filter { r -> keys.any { r.contains(it) } }
            .distinct()
            .take(40)
            .joinToString("\n") { "  " + it.take(200) }
    } catch (t: Throwable) {
        "  (could not read crash trace: ${t.message})"
    }

    fun clear() {
        file.delete()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK).format(Date(ms))
}
