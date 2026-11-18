package app.wafflr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Timings from one read-aloud run, used to judge whether a voice is fast enough on the phone. */
data class SpeechResult(
    val voice: Voice,
    val threads: Int,
    val speed: Float,
    val chars: Int,
    val loadMs: Long,
    val firstAudioMs: Long,
    val synthMs: Long,
    val audioSeconds: Double,
    val stopped: Boolean,
) {
    val rtf: Double get() = if (audioSeconds > 0) synthMs / 1000.0 / audioSeconds else 0.0

    fun summary(): String = buildString {
        append(voice.label)
        append(" | threads ").append(threads)
        append(" | speed ").append("%.1f".format(speed))
        append(" | ").append(chars).append(" chars")
        append("\n  model load: ").append(if (loadMs > 0) "$loadMs ms" else "already loaded")
        append("\n  time to first audio: ").append(firstAudioMs).append(" ms")
        append("\n  synthesis: ").append("%.1f".format(synthMs / 1000.0)).append(" s for ")
        append("%.1f".format(audioSeconds)).append(" s of speech")
        append(" (real-time factor ").append("%.2f".format(rtf)).append(if (rtf < 1) ", faster than real time)" else ", SLOWER than real time)")
        if (stopped) append("\n  (stopped early)")
    }
}

interface SpeechListener {
    fun onStatus(text: String) {}
    fun onDone(result: SpeechResult) {}
    fun onError(message: String) {}
}

/**
 * Wraps sherpa-onnx. Generates speech sentence by sentence and streams each sentence to the
 * speaker while the next one is being generated.
 */
class SpeechEngine(private val store: VoiceStore) {
    private val worker = Executors.newSingleThreadExecutor()
    private val stopFlag = AtomicBoolean(false)

    private var tts: OfflineTts? = null
    private var loadedKey: String? = null
    private var player: Player? = null

    @Volatile var isSpeaking = false
        private set

    fun speak(text: String, voice: Voice, speed: Float, threads: Int, listener: SpeechListener) {
        stop()
        worker.execute {
            stopFlag.set(false)
            isSpeaking = true
            try {
                if (!store.isInstalled(voice.pack)) {
                    store.install(voice.pack) { listener.onStatus(it) }
                }
                val key = "${voice.pack.id}|${voice.lexicon}|$threads"
                var loadMs = 0L
                if (key != loadedKey || tts == null) {
                    listener.onStatus("Loading ${voice.label}…")
                    tts?.release()
                    tts = null
                    val t0 = SystemClock.elapsedRealtime()
                    tts = OfflineTts(config = buildConfig(voice, threads))
                    loadMs = SystemClock.elapsedRealtime() - t0
                    loadedKey = key
                }
                val engine = tts!!
                val p = Player(engine.sampleRate()).also { player = it }
                listener.onStatus("Speaking…")
                val start = SystemClock.elapsedRealtime()
                var firstAudio = -1L
                var samples = 0L
                // sherpa-onnx's native code looks up a method with the exact signature
                // invoke(float[]): Integer. A plain Kotlin lambda doesn't have one (Kotlin 2
                // compiles lambdas without it), which crashed the app, so use an explicit class.
                val onChunk = object : (FloatArray) -> Int {
                    override fun invoke(chunk: FloatArray): Int {
                        if (firstAudio < 0) firstAudio = SystemClock.elapsedRealtime() - start
                        samples += chunk.size
                        p.enqueue(chunk.copyOf())
                        return if (stopFlag.get()) 0 else 1
                    }
                }
                engine.generateWithCallback(text = text, sid = voice.sid, speed = speed, callback = onChunk)
                val synthMs = SystemClock.elapsedRealtime() - start
                p.finish()
                p.awaitEnd()
                listener.onDone(
                    SpeechResult(
                        voice = voice, threads = threads, speed = speed, chars = text.length,
                        loadMs = loadMs, firstAudioMs = firstAudio.coerceAtLeast(0), synthMs = synthMs,
                        audioSeconds = samples.toDouble() / engine.sampleRate(), stopped = stopFlag.get(),
                    )
                )
            } catch (t: Throwable) {
                listener.onError(t.message ?: t.toString())
            } finally {
                player = null
                isSpeaking = false
            }
        }
    }

    fun stop() {
        stopFlag.set(true)
        player?.stopNow()
    }

    private fun buildConfig(voice: Voice, threads: Int): OfflineTtsConfig {
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
                    ),
                    numThreads = threads,
                    provider = "cpu",
                )
            }
            EngineKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
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

    /** Plays float PCM chunks on its own thread so generation never waits for the speaker. */
    private class Player(sampleRate: Int) {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val end = FloatArray(0)
        private val stopped = AtomicBoolean(false)
        private val track: AudioTrack
        private val thread: Thread

        init {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
            track.play()
            thread = Thread {
                try {
                    while (!stopped.get()) {
                        val chunk = queue.take()
                        if (chunk === end) break
                        var off = 0
                        while (off < chunk.size && !stopped.get()) {
                            val n = track.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            off += n
                        }
                    }
                    if (!stopped.get()) {
                        // Let the last buffer drain before releasing.
                        track.stop()
                        Thread.sleep(300)
                    }
                } catch (_: InterruptedException) {
                } finally {
                    try { track.pause(); track.flush() } catch (_: Throwable) {}
                    track.release()
                }
            }.apply { name = "wafflr-player"; start() }
        }

        fun enqueue(chunk: FloatArray) { if (!stopped.get()) queue.put(chunk) }
        fun finish() { queue.put(end) }
        fun awaitEnd() { thread.join() }
        fun stopNow() {
            stopped.set(true)
            queue.clear()
            queue.put(end)
            // Unblocks a write that is waiting on the speaker.
            try { track.pause(); track.flush() } catch (_: Throwable) {}
            thread.interrupt()
        }
    }

    companion object {
        fun deviceLine(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
