package app.wafflr

import android.app.Application

class WafflrApp : Application() {
    lateinit var voices: VoiceStore
        private set
    lateinit var speech: SpeechEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReport.install(this)
        voices = VoiceStore(this)
        speech = SpeechEngine(voices)
        SelectionLog.init(this)
    }

    companion object {
        lateinit var instance: WafflrApp
            private set
    }
}
