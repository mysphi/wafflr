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
    /** Kokoro only: the model file inside the pack. */
    val modelFile: String = "model.onnx",
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
    val piperJenny = piper("jenny", "en_GB-jenny_dioco-medium")
    val piperAlba = piper("alba", "en_GB-alba-medium")
    val piperCori = piper("cori", "en_GB-cori-medium")
    val piperNorthern = piper("northern-male", "en_GB-northern_english_male-medium")
    val piperSouthern = piper("southern-female", "en_GB-southern_english_female-low")
    val piperAmy = piper("amy", "en_US-amy-medium")
    val piperKristin = piper("kristin", "en_US-kristin-medium")
    val piperRyan = piper("ryan", "en_US-ryan-medium")
    val piperJoe = piper("joe", "en_US-joe-medium")
    // The int8 (compressed) Kokoro model: about a third of the size of the full one.
    val kokoro = VoicePack(
        "kokoro-v1-int8", EngineKind.KOKORO, "kokoro-int8-multi-lang-v1_0", bundled = false,
        sizeNote = "about 126 MB download, use Wi-Fi; slower to start than Piper",
        modelFile = "model.int8.onnx",
    )

    val packs = listOf(
        piperAlan, piperJenny, piperAlba, piperCori, piperNorthern, piperSouthern,
        piperAmy, piperKristin, piperRyan, piperJoe, kokoro,
    )

    /** Each Piper voice is its own small download (one voice per pack). */
    private fun piper(id: String, name: String) =
        VoicePack("piper-$id", EngineKind.PIPER, "vits-piper-$name", bundled = false, sizeNote = "about 65 MB download")

    private const val GB = "lexicon-gb-en.txt"
    private const val US = "lexicon-us-en.txt"

    // Kokoro v1.0 speaker ids, matching the sample clips you listened to.
    // lang stays empty: setting it makes sherpa-onnx skip the lexicon and hand every word to
    // espeak with that voice name, which crashed natively on the phone. The lexicon picks the accent.
    val voices = listOf(
        Voice("piper-alan", "Piper · Alan (British male)", piperAlan),
        Voice("piper-jenny", "Piper · Jenny (British female)", piperJenny),
        Voice("piper-alba", "Piper · Alba (Scottish female)", piperAlba),
        Voice("piper-cori", "Piper · Cori (British female)", piperCori),
        Voice("piper-northern-male", "Piper · Northern English male", piperNorthern),
        Voice("piper-southern-female", "Piper · Southern English female (lighter quality)", piperSouthern),
        Voice("piper-amy", "Piper · Amy (American female)", piperAmy),
        Voice("piper-kristin", "Piper · Kristin (American female)", piperKristin),
        Voice("piper-ryan", "Piper · Ryan (American male)", piperRyan),
        Voice("piper-joe", "Piper · Joe (American male)", piperJoe),
        Voice("kokoro-george", "Kokoro · George (British male)", kokoro, sid = 26, lexicon = GB, lang = ""),
        Voice("kokoro-daniel", "Kokoro · Daniel (British male)", kokoro, sid = 24, lexicon = GB, lang = ""),
        Voice("kokoro-emma", "Kokoro · Emma (British female)", kokoro, sid = 21, lexicon = GB, lang = ""),
        Voice("kokoro-lily", "Kokoro · Lily (British female)", kokoro, sid = 23, lexicon = GB, lang = ""),
        Voice("kokoro-michael", "Kokoro · Michael (American male)", kokoro, sid = 16, lexicon = US, lang = ""),
        Voice("kokoro-heart", "Kokoro · Heart (American female)", kokoro, sid = 3, lexicon = US, lang = ""),
    )

    val default: Voice = voices.first()

    /** Name for a pack in the list of downloaded voices, e.g. "Piper · Alan (British male)". */
    fun title(pack: VoicePack): String {
        val inPack = voices.filter { it.pack == pack }
        return if (inPack.size == 1) inPack[0].label
        else "Kokoro: " + inPack.joinToString(", ") { it.label.substringAfter("· ").substringBefore(" (") }
    }

    /** Old pack folders that are no longer offered (e.g. the full-size Kokoro), safe to remove. */
    val retiredDirs = listOf("kokoro-multi-lang-v1_0")

    fun byId(id: String?): Voice = voices.firstOrNull { it.id == id } ?: default
}
