package app.wafflr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

enum class ReaderState { IDLE, PREPARING, PLAYING, PAUSED }

data class ReaderStatus(
    val state: ReaderState,
    val index: Int = 0,
    val total: Int = 0,
    val message: String = "",
)

/** Timings from one read, used to compare voices on the phone. */
data class SpeechResult(
    val voice: Voice,
    val threads: Int,
    val speed: Float,
    val chars: Int,
    val source: String,
    val loadMs: Long,
    val firstAudioMs: Long,
    val synthMs: Long,
    val audioSeconds: Double,
    val finished: Boolean,
) {
    val rtf: Double get() = if (audioSeconds > 0) synthMs / 1000.0 / audioSeconds else 0.0

    fun summary(): String = buildString {
        append(voice.label).append(" | ").append(source)
        append(" | threads ").append(threads)
        append(" | speed ").append("%.1f".format(speed))
        append(" | ").append(chars).append(" chars")
        append("\n  model load: ").append(if (loadMs > 0) "$loadMs ms" else "already loaded")
        append("\n  time to first audio: ").append(firstAudioMs).append(" ms")
        append("\n  synthesis: ").append("%.1f".format(synthMs / 1000.0)).append(" s for ")
        append("%.1f".format(audioSeconds)).append(" s of speech")
        append(" (real-time factor ").append("%.2f".format(rtf)).append(")")
        if (!finished) append("\n  (stopped before the end)")
    }
}

interface ReaderListener {
    fun onStatus(status: ReaderStatus) {}
    fun onFinished(result: SpeechResult) {}
    fun onError(message: String) {}
}

/**
 * Reads text aloud one sentence at a time. While one sentence plays, the next ones are generated,
 * so speech is continuous. Supports pause, resume, next/previous sentence and stop.
 * Only one read runs at a time; starting a new one stops the old one.
 */
class Reader(private val context: Context, private val models: Models) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<ReaderListener>()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var session: Session? = null

    @Volatile var status = ReaderStatus(ReaderState.IDLE)
        private set

    fun addListener(l: ReaderListener) { listeners += l; l.onStatus(status) }
    fun removeListener(l: ReaderListener) { listeners -= l }

    val isActive: Boolean get() = status.state != ReaderState.IDLE

    fun read(text: String, voice: Voice, speed: Float, threads: Int, source: String, tuning: Tuning = Tuning()) {
        stop()
        val sentences = TextSplitter.split(text)
        if (sentences.isEmpty()) {
            publishError("There is no text to read.")
            return
        }
        // Note non-default tuning in the test report.
        val label = if (tuning == Tuning()) source
            else "$source, pitch %.2f, expression %.2f".format(tuning.pitch, tuning.expression)
        val s = Session(sentences, voice, speed, threads, label, text.length, tuning)
        session = s
        s.start()
    }

    fun togglePause() {
        val s = session ?: return
        if (s.paused) s.resume() else s.pause()
    }

    fun pause() { session?.pause() }
    fun resume() { session?.resume() }
    fun next() { session?.jump(+1) }
    fun previous() { session?.jump(-1) }

    fun stop() {
        val s = session ?: return
        s.stop()
        session = null
        status = ReaderStatus(ReaderState.IDLE)
        main.post { listeners.forEach { it.onStatus(status) } }
    }

    private fun publish(st: ReaderStatus, from: Session) {
        if (session !== from && st.state != ReaderState.IDLE) return
        status = st
        main.post { listeners.forEach { it.onStatus(st) } }
    }

    private fun publishError(message: String) {
        main.post { listeners.forEach { it.onError(message) } }
    }

    private inner class Session(
        val sentences: List<String>,
        val voice: Voice,
        val speed: Float,
        val threads: Int,
        val source: String,
        val chars: Int,
        val tuning: Tuning,
    ) {
        private val lock = Object()
        private var cur = 0
        private var jumpTo = -1
        @Volatile var paused = false
            private set
        private var stopped = false
        private var pausedByFocus = false
        private val cache = HashMap<Int, FloatArray>()
        private var genError: Throwable? = null
        @Volatile private var track: AudioTrack? = null
        private var focusRequest: AudioFocusRequest? = null

        private var startedAt = 0L
        private var firstAudioMs = -1L
        private var synthMs = 0L
        private var audioSamples = 0L
        private val counted = HashSet<Int>()

        fun start() {
            Thread({ runPlayer() }, "wafflr-player").start()
        }

        fun pause() {
            synchronized(lock) {
                if (stopped || paused) return
                paused = true
                lock.notifyAll()
            }
            try { track?.pause() } catch (_: Throwable) {}
            publish(ReaderStatus(ReaderState.PAUSED, cur, sentences.size), this)
        }

        fun resume() {
            synchronized(lock) {
                if (stopped || !paused) return
                paused = false
                pausedByFocus = false
                lock.notifyAll()
            }
            try { track?.play() } catch (_: Throwable) {}
            publish(ReaderStatus(ReaderState.PLAYING, cur, sentences.size), this)
        }

        fun jump(delta: Int) {
            synchronized(lock) {
                if (stopped) return
                jumpTo = (cur + delta).coerceIn(0, sentences.size - 1)
                lock.notifyAll()
            }
            // Drop the rest of the current sentence straight away (also frees a blocked write).
            try { track?.let { it.pause(); it.flush(); if (!paused) it.play() } } catch (_: Throwable) {}
        }

        fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                lock.notifyAll()
            }
            try { track?.pause(); track?.flush() } catch (_: Throwable) {}
        }

        private fun runPlayer() {
            var model: LoadedModel? = null
            var finished = false
            try {
                publish(ReaderStatus(ReaderState.PREPARING, 0, sentences.size, "Getting the voice ready…"), this)
                model = models.get(voice, threads, tuning.expression) { msg ->
                    publish(ReaderStatus(ReaderState.PREPARING, 0, sentences.size, msg), this)
                }
                if (isStopped()) return
                startedAt = SystemClock.elapsedRealtime()
                Thread({ runGenerator(model) }, "wafflr-generator").start()
                val t = createTrack(model.sampleRate).also { track = it }
                requestFocus()
                t.play()
                finished = playLoop(t, model.sampleRate)
            } catch (e: Throwable) {
                val log = Models.engineLog()
                publishError((e.message ?: e.toString()) + if (log.isNotEmpty()) "\nEngine log:\n$log" else "")
            } finally {
                synchronized(lock) { stopped = true; lock.notifyAll() }
                try { track?.release() } catch (_: Throwable) {}
                track = null
                abandonFocus()
                if (model != null && startedAt > 0) {
                    val loadMs = if (!model.loadReported) model.loadMs else 0
                    model.loadReported = true
                    val result = SpeechResult(
                        voice, threads, speed, chars, source, loadMs,
                        firstAudioMs.coerceAtLeast(0), synthMs, audioSamples.toDouble() / model.sampleRate, finished,
                    )
                    main.post { listeners.forEach { it.onFinished(result) } }
                }
                val wasCurrent = session === this
                if (wasCurrent) session = null
                if (wasCurrent || session == null) publish(ReaderStatus(ReaderState.IDLE), this)
            }
        }

        /** Returns true if every sentence was played. */
        private fun playLoop(t: AudioTrack, sampleRate: Int): Boolean {
            var i = 0
            val n = sentences.size
            while (true) {
                synchronized(lock) {
                    if (stopped) return false
                    if (jumpTo >= 0) { i = jumpTo; jumpTo = -1 }
                    cur = i
                    lock.notifyAll() // wake the generator: the window moved
                }
                if (i >= n) break
                publish(ReaderStatus(if (paused) ReaderState.PAUSED else ReaderState.PLAYING, i, n), this)

                val audio: FloatArray = synchronized(lock) {
                    while (!stopped && jumpTo < 0 && cache[i] == null && genError == null) lock.wait()
                    genError?.let { throw it }
                    if (stopped) return false
                    if (jumpTo >= 0) null else cache[i]
                } ?: continue

                var off = 0
                var jumped = false
                while (off < audio.size) {
                    synchronized(lock) {
                        while (paused && !stopped && jumpTo < 0) lock.wait()
                        if (stopped) return false
                        if (jumpTo >= 0) jumped = true
                    }
                    if (jumped) break
                    val w = t.write(audio, off, minOf(BLOCK, audio.size - off), AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
                if (jumped) {
                    t.pause(); t.flush()
                    if (!paused) t.play()
                    continue
                }
                i++
            }
            // Let the last sentence finish playing before releasing the speaker.
            val drainMs = t.bufferSizeInFrames * 1000L / sampleRate + 150
            var waited = 0L
            while (waited < drainMs) {
                synchronized(lock) {
                    while (paused && !stopped) lock.wait()
                    if (stopped) return true
                }
                Thread.sleep(50); waited += 50
            }
            return true
        }

        private fun runGenerator(model: LoadedModel) {
            try {
                while (true) {
                    var target = -1
                    synchronized(lock) {
                        while (true) {
                            if (stopped) return
                            val end = minOf(sentences.size, cur + LOOKAHEAD)
                            target = (cur until end).firstOrNull { it !in cache } ?: -1
                            if (target >= 0) break
                            lock.wait()
                        }
                        cache.keys.removeAll { it < cur - 2 }
                    }
                    val t0 = SystemClock.elapsedRealtime()
                    val audio = PitchShift.apply(
                        models.generate(model, sentences[target], voice.sid, speed), model.sampleRate, tuning.pitch,
                    )
                    val took = SystemClock.elapsedRealtime() - t0
                    synchronized(lock) {
                        cache[target] = audio
                        if (counted.add(target)) {
                            synthMs += took
                            audioSamples += audio.size
                        }
                        if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - startedAt
                        lock.notifyAll()
                    }
                }
            } catch (e: Throwable) {
                synchronized(lock) { genError = e; lock.notifyAll() }
            }
        }

        private fun isStopped() = synchronized(lock) { stopped }

        private fun createTrack(sampleRate: Int): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            return AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }

        private fun requestFocus() {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            if (!paused) { pausedByFocus = true; pause() }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) resume()
                    }
                }, main)
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        }

        private fun abandonFocus() {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    companion object {
        private const val BLOCK = 2048
        private const val LOOKAHEAD = 3
        private val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
