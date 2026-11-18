package app.wafflr

/**
 * Splits text into short pieces to speak one at a time. Short pieces mean speech starts sooner,
 * and give natural points to pause, skip forward or go back.
 */
object TextSplitter {
    private const val MAX_LEN = 280
    // Splits after . ! ? when the next word starts with a capital or number, except after
    // common abbreviations such as "Mr." or "Dr.".
    private val sentenceEnd = Regex("""(?<!\b(?:Mr|Mrs|Ms|Dr|St|Prof|Sr|Jr|vs|etc|No|Fig|Mt|Rev|Gen|Col|Capt|Lt|Sgt)\.)(?<=[.!?…])["'”’)\]]*\s+(?=["'“‘(\[]?[\p{Lu}\p{N}])""")
    private val softBreak = Regex("""(?<=[,;:—–])\s+""")
    private val spaces = Regex("""[ \t ]+""")

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split(Regex("""\n\s*\n|\n"""))) {
            val clean = para.replace(spaces, " ").trim()
            if (clean.isEmpty()) continue
            for (sentence in clean.split(sentenceEnd)) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                if (s.length <= MAX_LEN) out += s else out += breakLong(s)
            }
        }
        return out.filter { it.any { c -> c.isLetterOrDigit() } }
    }

    private fun breakLong(s: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in s.split(softBreak)) {
            if (current.isNotEmpty() && current.length + piece.length + 1 > MAX_LEN) {
                parts += current.toString(); current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) parts += current.toString()
        // Still too long (no commas): cut at spaces.
        return parts.flatMap { p ->
            if (p.length <= MAX_LEN) listOf(p) else p.split(' ').chunked(40).map { it.joinToString(" ") }
        }
    }
}
