package app.wafflr

import android.os.Build
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** A loaded voice model. Generation is not thread-safe, so callers use one thread at a time. */
class LoadedModel(val tts: OfflineTts, val key: String, val loadMs: Long) {
    val sampleRate: Int = tts.sampleRate()
    /** True once a run has reported this model's load time. */
    var loadReported = false
}

/** Loads and caches one sherpa-onnx voice model at a time (they are large). */
class Models(private val store: VoiceStore) {
    private var current: LoadedModel? = null

    /** Speaks one piece of text. Synchronized with [get] so a model is never freed mid-use. */
    @Synchronized
    fun generate(model: LoadedModel, text: String, sid: Int, speed: Float): FloatArray =
        model.tts.generate(text = text, sid = sid, speed = speed).samples

    /** Frees the loaded model if it comes from [pack], e.g. before its files are removed. */
    @Synchronized
    fun release(pack: VoicePack) {
        val c = current ?: return
        if (c.key.startsWith(pack.id + "|")) {
            c.tts.release()
            current = null
        }
    }

    /** Blocking. Downloads/unpacks the voice if needed, then loads it. */
    @Synchronized
    fun get(voice: Voice, threads: Int, expression: Float = Tuning.DEFAULT_EXPRESSION, progress: (String) -> Unit): LoadedModel {
        if (!store.isInstalled(voice.pack)) store.install(voice.pack, progress)
        val key = "${voice.pack.id}|${voice.lexicon}|$threads|$expression"
        current?.let { if (it.key == key) return it }
        progress("Loading voice…")
        current?.tts?.release()
        current = null
        checkFiles(voice)
        val t0 = SystemClock.elapsedRealtime()
        val tts = OfflineTts(config = buildConfig(voice, threads, expression))
        // If sherpa-onnx rejects the config it returns a null engine instead of throwing,
        // and the next call would crash the whole app. Check first.
        if (nativePointer(tts) == 0L) error("The voice engine could not load ${voice.label}.")
        return LoadedModel(tts, key, SystemClock.elapsedRealtime() - t0).also { current = it }
    }

    /** Same checks sherpa-onnx makes, but with a readable message instead of a crash. */
    private fun checkFiles(voice: Voice) {
        val dir = store.dirFor(voice.pack)
        val needed = mutableListOf("tokens.txt", "espeak-ng-data/phontab", "espeak-ng-data/phondata", "espeak-ng-data/phonindex", "espeak-ng-data/intonations")
        if (voice.pack.kind == EngineKind.KOKORO) {
            needed += listOf(voice.pack.modelFile, "voices.bin")
            if (voice.lexicon.isNotEmpty()) needed += voice.lexicon
        }
        val missing = needed.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            store.delete(voice.pack) // force a clean re-download next time
            val present = dir.list()?.sorted()?.joinToString(", ") ?: "folder missing"
            error("Voice files incomplete: missing ${missing.joinToString(", ")}. Found: $present. Try again to download them.")
        }
    }

    private fun nativePointer(t: OfflineTts): Long = try {
        OfflineTts::class.java.getDeclaredField("ptr").apply { isAccessible = true }.getLong(t)
    } catch (_: Throwable) {
        -1L // can't tell; carry on
    }

    private fun buildConfig(voice: Voice, threads: Int, expression: Float): OfflineTtsConfig {
        val dir = store.dirFor(voice.pack)
        val espeak = File(dir, "espeak-ng-data").absolutePath
        val model = when (voice.pack.kind) {
            EngineKind.PIPER -> {
                val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
                    ?: error("No .onnx model in $dir")
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = onnx.absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = espeak,
                        noiseScale = expression,
                    ),
                    numThreads = threads,
                    provider = "cpu",
                )
            }
            EngineKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, voice.pack.modelFile).absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = espeak,
                    lexicon = if (voice.lexicon.isNotEmpty()) File(dir, voice.lexicon).absolutePath else "",
                    lang = voice.lang,
                ),
                numThreads = threads,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, maxNumSentences = 1)
    }

    companion object {
        fun deviceLine(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        /** Last lines the voice engine wrote to the system log (only this app's own lines). */
        fun engineLog(): String = try {
            val proc = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "brief", "--pid", android.os.Process.myPid().toString())
                .redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            lines.filter { it.contains("sherpa", ignoreCase = true) || it.contains("onnx", ignoreCase = true) || it.contains("Error") }
                .filterNot { it.contains("config:") }
                .takeLast(25)
                .joinToString("\n") { it.take(300) }
        } catch (_: Throwable) {
            ""
        }
    }
}
