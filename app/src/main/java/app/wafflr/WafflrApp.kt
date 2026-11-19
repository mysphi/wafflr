package app.wafflr

import android.app.Application

class WafflrApp : Application() {
    lateinit var voices: VoiceStore
        private set
    lateinit var models: Models
        private set
    lateinit var reader: Reader
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var mediaControls: MediaControls
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReport.install(this)
        prefs = Prefs(this)
        voices = VoiceStore(this)
        models = Models(voices)
        reader = Reader(this, models)
        mediaControls = MediaControls(this, reader)
        Thread({
            try { voices.housekeeping() } catch (_: Throwable) {}
        }, "voice-housekeeping").start()
        // Keep a log of every read so it can be copied into a test report.
        reader.addListener(object : ReaderListener {
            override fun onFinished(result: SpeechResult) = prefs.addResult(result.summary())
            override fun onError(message: String) = prefs.addResult("ERROR: $message")
        })
    }

    companion object {
        lateinit var instance: WafflrApp
            private set
    }
}
