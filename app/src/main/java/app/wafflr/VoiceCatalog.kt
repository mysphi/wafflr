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
) {
    /** The language the voice speaks, e.g. "en" for en_GB and en_US. Pronunciation fixes are kept per language. */
    val language: String
        get() = Regex("piper-([a-z]{2,3})_").find(pack.dirName)?.groupValues?.get(1) ?: "en"
}

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
    val packs = listOf(
        piperAlan, piperJenny, piperAlba, piperCori, piperNorthern, piperSouthern,
        piperAmy, piperKristin, piperRyan, piperJoe,
    )

    /** Each Piper voice is its own small download (one voice per pack). */
    private fun piper(id: String, name: String) =
        VoicePack("piper-$id", EngineKind.PIPER, "vits-piper-$name", bundled = false, sizeNote = "about 65 MB download")

    val voices = listOf(
        Voice("piper-alan", "Alan (Southern English Male)", piperAlan),
        Voice("piper-jenny", "Jenny (Irish Female)", piperJenny),
        Voice("piper-alba", "Alba (Scottish Female)", piperAlba),
        Voice("piper-cori", "Cori (English Female)", piperCori),
        Voice("piper-northern-male", "Geoff (Northern English Male)", piperNorthern),
        Voice("piper-southern-female", "Karen (Southern English Female)", piperSouthern),
        Voice("piper-amy", "Amy (American Female)", piperAmy),
        Voice("piper-kristin", "Kristin (American Female)", piperKristin),
        Voice("piper-ryan", "Ryan (American Male)", piperRyan),
        Voice("piper-joe", "Joe (American Male)", piperJoe),
    )

    val default: Voice = voices.first()

    /** Name for a pack in the list of downloaded voices, e.g. "Alan (Southern English Male)". */
    fun title(pack: VoicePack): String = voices.filter { it.pack == pack }.joinToString(", ") { it.label }

    /** Old pack folders that are no longer offered (the Kokoro voices), safe to remove. */
    val retiredDirs = listOf("kokoro-multi-lang-v1_0", "kokoro-int8-multi-lang-v1_0")

    fun byId(id: String?): Voice = voices.firstOrNull { it.id == id } ?: default
}
