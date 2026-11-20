package app.wafflr

/**
 * Changes text just before it goes to the voice, so tricky words come out right.
 * Only what is spoken changes: the words shown, highlighted and underlined stay as they are.
 *
 * Corrections belong to a language (the voice's, e.g. "en"), because a fix for English would be
 * wrong in French. Three layers, applied in this order:
 *  1. the person's own pronunciations (Settings → Pronunciations), so they can override anything;
 *  2. a built-in list of words the voices get wrong;
 *  3. rules for patterns: money, units, abbreviations, web addresses.
 *
 * Plain Kotlin with no Android calls, so it can be checked off the phone. It takes well under a
 * millisecond per sentence, so speech stays real time.
 */
object Pronounce {
    data class Entry(val word: String, val sayAs: String)

    /** Words the voices are known to get wrong, by language. Spoken as the respelling. */
    val builtIn: Map<String, List<Entry>> = mapOf(
        "en" to listOf(
            Entry("Leicester", "Lester"),
            Entry("Leicestershire", "Lestershur"),
            Entry("Gloucester", "Gloster"),
            Entry("Gloucestershire", "Glostershur"),
            Entry("Worcester", "Wooster"),
            Entry("Worcestershire", "Woostershur"),
        ),
    )

    @Volatile private var user: Map<String, List<Entry>> = emptyMap()
    private val compiled = HashMap<String, List<Pair<Regex, String>>>()

    /** Sets the person's own pronunciations for [lang]. */
    fun setUser(lang: String, entries: List<Entry>) {
        synchronized(compiled) {
            user = user + (lang to entries)
            compiled.remove(lang)
        }
    }

    /** The text to give the voice for [text], in language [lang]. */
    fun forSpeech(text: String, lang: String): String {
        var s = text
        for ((re, say) in wordRules(lang)) s = re.replace(s) { say }
        if (lang == "en") s = english(s)
        return s
    }

    /** Matches [word] as a whole word, ignoring case. */
    fun wordRegex(word: String) =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    private fun wordRules(lang: String): List<Pair<Regex, String>> = synchronized(compiled) {
        compiled.getOrPut(lang) {
            val mine = user[lang].orEmpty().filter { it.word.isNotBlank() && it.sayAs.isNotBlank() }
            val mineWords = mine.map { it.word.trim().lowercase() }.toSet()
            // The person's own come first; a built-in word they've set themselves is skipped.
            (mine + builtIn[lang].orEmpty().filter { it.word.lowercase() !in mineWords })
                // Longer words first, so "Worcestershire" isn't caught by "Worcester".
                .sortedByDescending { it.word.length }
                .map { wordRegex(it.word) to it.sayAs.trim().replace("$", "\\$") }
        }
    }

    // --- English patterns ------------------------------------------------------------------

    private val currency = listOf(
        Triple("£", "pound", "pounds"), Triple("$", "dollar", "dollars"), Triple("€", "euro", "euros"),
    )
    private val money = Regex("([£$€])\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s?(k|m|bn|million|billion|thousand)?(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
    private val units = listOf(
        "km" to "kilometres", "kg" to "kilograms", "mph" to "miles per hour", "kph" to "kilometres per hour",
        "km/h" to "kilometres per hour", "cm" to "centimetres", "mm" to "millimetres", "ml" to "millilitres",
        "kWh" to "kilowatt hours", "kW" to "kilowatts", "GB" to "gigabytes", "MB" to "megabytes",
        "°C" to "degrees Celsius", "°F" to "degrees Fahrenheit", "°" to "degrees",
    )
    private val unitRegex = units.map { (u, say) ->
        Regex("(\\d)\\s?" + Regex.escape(u) + "(?![\\p{L}])") to "$1 $say"
    }
    private val abbreviations = listOf(
        Regex("\\be\\.g\\.?(?=\\s|,|$)", RegexOption.IGNORE_CASE) to "for example",
        Regex("\\bi\\.e\\.?(?=\\s|,|$)", RegexOption.IGNORE_CASE) to "that is",
        Regex("\\betc\\.(?=\\s|$)") to "et cetera",
        Regex("\\betc\\b") to "et cetera",
        Regex("\\bvs\\.?(?=\\s)") to "versus",
        Regex("\\bapprox\\.?(?=\\s)") to "approximately",
        Regex("\\bDr\\.?(?=\\s+\\p{Lu})") to "Doctor",
        Regex("\\bProf\\.?(?=\\s+\\p{Lu})") to "Professor",
        Regex("\\bNo\\.\\s?(?=\\d)") to "number ",
        // "St" before a name is Saint; after one it's Street.
        Regex("\\bSt\\.?(?=\\s+\\p{Lu})") to "Saint",
        Regex("(?<=\\p{Lu}\\p{Ll}{1,30}\\s)St\\b") to "Street",
        Regex("\\s&\\s") to " and ",
    )
    private val url = Regex("\\b(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|co\\.uk|org\\.uk|gov\\.uk|ac\\.uk|uk|io|ai|app|dev|edu|gov)(?:/[^\\s]*)?)", RegexOption.IGNORE_CASE)
    private val email = Regex("\\b([\\w.+-]+)@([\\w-]+(?:\\.[\\w-]+)+)\\b")

    private fun english(text: String): String {
        var s = text
        s = email.replace(s) { m -> m.groupValues[1].replace(".", " dot ") + " at " + m.groupValues[2].replace(".", " dot ") }
        s = url.replace(s) { m ->
            m.groupValues[1].trimEnd('/', '.', ',')
                .replace(".", " dot ").replace("/", " slash ").replace("-", " ")
                .replace(Regex("\\s+"), " ").trim()
        }
        s = money.replace(s) { m -> sayMoney(m) }
        for ((re, say) in unitRegex) s = re.replace(s, say)
        for ((re, say) in abbreviations) s = re.replace(s, say)
        return s
    }

    private fun sayMoney(m: MatchResult): String {
        val (_, one, many) = currency.first { it.first == m.groupValues[1] }
        val whole = m.groupValues[2].replace(",", "")
        val pence = m.groupValues[3]
        val scale = when (m.groupValues[4].lowercase()) {
            "k", "thousand" -> " thousand"
            "m", "million" -> " million"
            "bn", "billion" -> " billion"
            else -> ""
        }
        if (scale.isNotEmpty()) {
            val n = if (pence.isNotEmpty()) "$whole point ${pence.toList().joinToString(" ")}" else whole
            return "$n$scale $many"
        }
        val unit = if (whole == "1") one else many
        return if (pence.isNotEmpty() && pence.trimEnd('0').isNotEmpty()) "$whole $unit ${pence.padEnd(2, '0').trimStart('0')}" else "$whole $unit"
    }
}
