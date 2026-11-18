package app.wafflr

enum class EngineKind { PIPER, KOKORO }

/** A downloadable (or bundled) folder of model files. One pack can hold several voices. */
data class VoicePack(
    val id: String,
    val kind: EngineKind,
    /** Folder name inside the .tar.bz2 and under internal storage. */
    val dirName: String,
    val bundled: Boolean,
    val sizeNote: String,
) {
    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dirName.tar.bz2"
}

data class Voice(
    val id: String,
    val label: String,
    val pack: VoicePack,
    /** Speaker id inside the model (Kokoro has many speakers in one file). */
    val sid: Int = 0,
    /** Kokoro only: pronunciation dictionary and espeak language for the accent. */
    val lexicon: String = "",
    val lang: String = "",
)

object VoiceCatalog {
    val piperAlan = VoicePack("piper-alan", EngineKind.PIPER, "vits-piper-en_GB-alan-medium", bundled = true, sizeNote = "built in")
    val piperJenny = VoicePack("piper-jenny", EngineKind.PIPER, "vits-piper-en_GB-jenny_dioco-medium", bundled = false, sizeNote = "about 60 MB download")
    val kokoro = VoicePack("kokoro-v1", EngineKind.KOKORO, "kokoro-multi-lang-v1_0", bundled = false, sizeNote = "large download, several hundred MB, use Wi-Fi")

    val packs = listOf(piperAlan, piperJenny, kokoro)

    private const val GB = "lexicon-gb-en.txt"
    private const val US = "lexicon-us-en.txt"

    // Kokoro v1.0 speaker ids, matching the sample clips you listened to.
    val voices = listOf(
        Voice("piper-alan", "Piper · Alan (British male)", piperAlan),
        Voice("piper-jenny", "Piper · Jenny (British female)", piperJenny),
        Voice("kokoro-george", "Kokoro · George (British male)", kokoro, sid = 26, lexicon = GB, lang = "en-gb"),
        Voice("kokoro-daniel", "Kokoro · Daniel (British male)", kokoro, sid = 24, lexicon = GB, lang = "en-gb"),
        Voice("kokoro-emma", "Kokoro · Emma (British female)", kokoro, sid = 21, lexicon = GB, lang = "en-gb"),
        Voice("kokoro-lily", "Kokoro · Lily (British female)", kokoro, sid = 23, lexicon = GB, lang = "en-gb"),
        Voice("kokoro-michael", "Kokoro · Michael (American male)", kokoro, sid = 16, lexicon = US, lang = "en-us"),
        Voice("kokoro-heart", "Kokoro · Heart (American female)", kokoro, sid = 3, lexicon = US, lang = "en-us"),
    )

    val default: Voice = voices.first()

    fun byId(id: String?): Voice = voices.firstOrNull { it.id == id } ?: default
}
