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
        val line = "${stamp(info.timestamp)} last exit: $kind. ${info.description ?: ""}".trim()
        return if (saved.isNotEmpty() && info.reason == ApplicationExitInfo.REASON_CRASH) "$line\n$saved" else line
    }

    fun clear() {
        file.delete()
    }

    private fun stamp(ms: Long) = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK).format(Date(ms))
}
