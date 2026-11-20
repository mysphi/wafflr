package app.wafflr

/**
 * Second pass over the lines found on screen, after menus and buttons are gone. Drops the
 * small print around the main text that is still "text", but not worth hearing:
 *
 *  - image labels: "Image source, Getty Images", "Affinity Water logo", "Photo of ...";
 *  - article details: dates and times, "Published", "Updated 3 hours ago", "972 Comments",
 *    "4 min read", "By ...";
 *  - the lead-in above the first real sentence, such as a byline or an email's banner title
 *    ("Tarah Welsh, Housing correspondent", "Our water supplies are still under pressure").
 *    Greetings like "Hello" or "Dear Gareth" stay. If no line looks like a sentence (a list of
 *    headlines, say), nothing is dropped this way.
 *
 * Plain Kotlin with no Android calls, so it can be checked off the phone.
 */
object TextTidy {
    /** [kept]: positions in the input of the lines kept, so callers can keep data alongside. */
    class Result(val lines: List<String>, val skipped: Int, val kept: List<Int>)

    private val months = "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?"
    private val datePatterns = listOf(
        Regex("\\b\\d{1,2}(st|nd|rd|th)? ($months)\\b,? \\d{4}", RegexOption.IGNORE_CASE),
        Regex("\\b($months) \\d{1,2}(st|nd|rd|th)?,? \\d{4}", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{1,2}[:.]\\d{2}\\s*(am|pm|gmt|bst|utc)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"),
    )
    private val metaStart = Regex(
        "^(published|updated|last updated|posted|first published|modified|share|save|listen|by)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val metaWhole = listOf(
        Regex("^[\\d,.]+[kKmM]?\\s+(comments?|replies|reply|likes?|shares?|views?|reactions?)$", RegexOption.IGNORE_CASE),
        Regex("^\\d+\\s*(min|mins|minute|minutes)\\s+read$", RegexOption.IGNORE_CASE),
        Regex("^(updated\\s+)?\\d+\\s*(seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)\\s+ago$", RegexOption.IGNORE_CASE),
        Regex("^(just now|yesterday|today)$", RegexOption.IGNORE_CASE),
    )
    private val imageLabel = listOf(
        Regex("^(image|photo|picture|video|media)\\s+(source|caption|credit)s?\\b", RegexOption.IGNORE_CASE),
        Regex("^(image|photo|picture|illustration|graphic)\\s+(of|showing)\\b", RegexOption.IGNORE_CASE),
        Regex("^(getty images|reuters|pa media|ap|afp|shutterstock|alamy|epa)$", RegexOption.IGNORE_CASE),
    )
    private val greeting = Regex("^(hi|hello|hey|dear|good (morning|afternoon|evening))\\b", RegexOption.IGNORE_CASE)

    fun tidy(lines: List<String>, options: ReadOptions = ReadOptions()): Result {
        var skipped = 0
        val kept = lines.indices.filter { i ->
            val line = lines[i]
            val drop = (!options.pictures && isImageLabel(line)) || (!options.details && isDetail(line))
            if (drop) skipped++
            !drop
        }
        // Lead-in: drop short non-sentences before the first real sentence.
        val first = if (options.leadIn) -1 else kept.indexOfFirst { isSentence(lines[it]) }
        val out = if (first <= 0) kept else kept.filterIndexed { k, i ->
            val keep = k >= first || greeting.containsMatchIn(lines[i])
            if (!keep) skipped++
            keep
        }
        return Result(out.map { lines[it] }, skipped, out)
    }

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    fun isImageLabel(s: String): Boolean {
        val t = s.trim()
        if (imageLabel.any { it.containsMatchIn(t) }) return true
        // "Affinity Water logo", "BBC News logo"
        return words(t) <= 6 && Regex("\\blogo\\.?$", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    /** Dates, "Published", "Updated 3 hours ago", "972 Comments" and similar short lines. */
    fun isDetail(s: String): Boolean {
        val t = s.trim()
        if (words(t) > 10) return false
        if (metaWhole.any { it.matches(t) }) return true
        if (metaStart.containsMatchIn(t) && words(t) <= 6) return true
        // A short line that is mostly a date or time, e.g. "19 November 2026, 02:02 GMT".
        return words(t) <= 7 && datePatterns.any { it.containsMatchIn(t) }
    }

    /** Looks like running text: a long line, or a few words ending like a sentence. */
    fun isSentence(s: String): Boolean {
        val t = s.trim().trimEnd('"', '\'', '”', '’', ')')
        val n = words(t)
        return n >= 10 || (n >= 4 && t.lastOrNull() in setOf('.', '!', '?', '…'))
    }
}
