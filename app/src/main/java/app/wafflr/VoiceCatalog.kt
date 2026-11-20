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
    /** SHA-256 of the download. A file that doesn't match is thrown away, never unpacked. */
    val sha256: String = "",
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
    val piperJenny = piper("jenny", "en_GB-jenny_dioco-medium", "a0888024569bafbefc05a4b48ddf8419d8dbbf3205f4af37cf7c6f1a87cc20c5")
    val piperAlba = piper("alba", "en_GB-alba-medium", "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1")
    val piperCori = piper("cori", "en_GB-cori-medium", "49c9a5361bbdd95d7ca9687c4de11e5908481f65e7c7c368960df79949fdac2b")
    val piperNorthern = piper("northern-male", "en_GB-northern_english_male-medium", "2bb2c1e709f58c11f17c693b3b38f500e110e7f54f2651774ec48b8d41f12c55")
    val piperSouthern = piper("southern-female", "en_GB-southern_english_female-low", "448e7723c66531c62004864382044b29703a2616699833ec7fe1205de9f549ce")
    val piperAmy = piper("amy", "en_US-amy-medium", "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264")
    val piperKristin = piper("kristin", "en_US-kristin-medium", "c2206f572df2956c50b1ae3367eebce3853c663e890cba8048cd62b1e4dbe6c7")
    val piperRyan = piper("ryan", "en_US-ryan-medium", "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505")
    val piperJoe = piper("joe", "en_US-joe-medium", "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3")
    val packs = listOf(
        piperAlan, piperJenny, piperAlba, piperCori, piperNorthern, piperSouthern,
        piperAmy, piperKristin, piperRyan, piperJoe,
    )

    /** Each Piper voice is its own small download (one voice per pack). */
    private fun piper(id: String, name: String, sha256: String) =
        VoicePack("piper-$id", EngineKind.PIPER, "vits-piper-$name", bundled = false, sizeNote = "about 65 MB download", sha256 = sha256)

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
