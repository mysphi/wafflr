package app.wafflr

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Works out when each word of a sentence is spoken, from the sentence's audio. The voices don't
 * report word timings, so this listens for them:
 *
 *  1. it finds where speech starts and ends (skipping the silence the voice adds at each end);
 *  2. it finds the pauses inside the sentence and lines them up with the punctuation that causes
 *     them (commas, semicolons, dashes...), which pins the clauses in place;
 *  3. within each clause, it shares the time out by each word's estimated syllables, with
 *     numbers counted as the longer words they are read as.
 *
 * Errors are then limited to within one clause instead of growing through the sentence.
 * Plain Kotlin with no Android calls, so it can be checked off the phone.
 */
object WordTimer {
    /** Word ranges in the sentence, and the audio frame each one starts at. */
    class Plan(val words: List<IntRange>, val starts: IntArray) {
        fun wordAt(frame: Int): IntRange? = indexAt(frame)?.let { words[it] }

        /** Which word (counting from 0) is being spoken at [frame]. */
        fun indexAt(frame: Int): Int? {
            if (words.isEmpty()) return null
            var i = 0
            while (i + 1 < starts.size && frame >= starts[i + 1]) i++
            return i
        }
    }

    private val WORD = Regex("\\S+")
    private const val WINDOW_MS = 10
    private const val MIN_PAUSE_MS = 90

    fun plan(sentence: String, audio: FloatArray, sampleRate: Int): Plan {
        val matches = WORD.findAll(sentence).toList()
        val words = matches.map { it.range }
        if (words.isEmpty() || audio.isEmpty()) return Plan(words, IntArray(words.size))

        // Loudness in 10 ms windows.
        val win = max(1, sampleRate * WINDOW_MS / 1000)
        val n = (audio.size + win - 1) / win
        val rms = FloatArray(n) { w ->
            var sum = 0.0
            val from = w * win
            val to = minOf(audio.size, from + win)
            for (k in from until to) sum += audio[k] * audio[k]
            sqrt(sum / (to - from)).toFloat()
        }
        val loud = rms.maxOrNull() ?: 0f
        val threshold = loud * 0.08f
        val speaking = BooleanArray(n) { rms[it] > threshold }
        val first = speaking.indexOfFirst { it }.takeIf { it >= 0 } ?: 0
        val last = speaking.indexOfLast { it }.takeIf { it >= 0 } ?: (n - 1)

        // Pauses inside the speech: runs of quiet windows.
        val pauses = mutableListOf<IntRange>() // in windows
        var w = first
        while (w <= last) {
            if (!speaking[w]) {
                val start = w
                while (w <= last && !speaking[w]) w++
                if ((w - start) * WINDOW_MS >= MIN_PAUSE_MS) pauses += start until w
            } else w++
        }

        // Clauses: words up to and including one that ends in pause-making punctuation.
        val clauses = mutableListOf<MutableList<Int>>(mutableListOf())
        for ((i, m) in matches.withIndex()) {
            clauses.last() += i
            val end = m.value.trimEnd('"', '\'', '”', '’', ')').lastOrNull()
            if (i < matches.size - 1 && end != null && end in ",;:—–.!?…") clauses += mutableListOf<Int>()
        }
        val breaks = clauses.size - 1

        // Match pauses to the punctuation breaks. Each break's expected time comes from the
        // syllables before it; a pause close to where a break should be, and long, is a good
        // match. A voice can run through a comma without pausing, and gaps inside words (the
        // closure before a "t" or "k") can look like short pauses, so a break may also go
        // unmatched, and position counts as much as length.
        val clauseWeight = clauses.map { c -> c.sumOf { syllables(matches[it].value).toDouble() } }
        val totalWeight = clauseWeight.sum().coerceAtLeast(0.001)
        val span = (last + 1 - first).toDouble()
        val expected = DoubleArray(breaks) { k ->
            first + span * clauseWeight.take(k + 1).sum() / totalWeight
        }
        val chosen = IntArray(breaks) { -1 } // pause index for each break, or -1
        if (breaks > 0 && pauses.isNotEmpty()) {
            val pCount = pauses.size
            fun cost(k: Int, j: Int): Double {
                val p = pauses[j]
                val centre = (p.first + p.last + 1) / 2.0
                val off = kotlin.math.abs(centre - expected[k]) / span
                val len = minOf((p.last - p.first + 1) * WINDOW_MS, 400) / 400.0
                return off * 3.0 - len - 0.1 // below zero: worth matching
            }
            val skip = 0.0 // a break with no pause
            // f[k][j]: best cost placing the first k breaks using only the first j pauses.
            val f = Array(breaks + 1) { DoubleArray(pCount + 1) }
            val how = Array(breaks + 1) { IntArray(pCount + 1) } // 0 skip pause, 1 skip break, 2 match
            for (k in 1..breaks) { f[k][0] = f[k - 1][0] + skip; how[k][0] = 1 }
            for (k in 0..breaks) for (j in 1..pCount) {
                var best = f[k][j - 1]; var h = 0
                if (k > 0) {
                    val unmatched = f[k - 1][j] + skip
                    if (unmatched < best) { best = unmatched; h = 1 }
                    val match = f[k - 1][j - 1] + cost(k - 1, j - 1)
                    if (match < best) { best = match; h = 2 }
                }
                f[k][j] = best; how[k][j] = h
            }
            var k = breaks; var j = pCount
            while (k > 0) {
                when (how[k][j]) {
                    0 -> j--
                    1 -> k--
                    else -> { chosen[k - 1] = j - 1; k--; j-- }
                }
            }
        }

        val starts = IntArray(words.size)
        fun spread(group: List<Int>, fromWin: Int, toWin: Int) {
            val weights = group.map { syllables(matches[it].value) }
            val total = weights.sum().coerceAtLeast(0.001f)
            var t = fromWin.toFloat()
            val width = (toWin - fromWin).coerceAtLeast(1).toFloat()
            for ((n, idx) in group.withIndex()) {
                starts[idx] = (t * win).toInt()
                t += width * weights[n] / total
            }
        }
        // Clauses between matched pauses are spread together.
        var from = first
        val group = mutableListOf<Int>()
        for ((c, clause) in clauses.withIndex()) {
            group += clause
            val pause = if (c < breaks) chosen[c] else -1
            if (pause >= 0) {
                spread(group, from, pauses[pause].first)
                from = pauses[pause].last + 1
                group.clear()
            }
        }
        if (group.isNotEmpty()) spread(group, from, last + 1)
        return Plan(words, starts)
    }

    /** A rough syllable count: vowel groups, with numbers and symbols read out as words. */
    fun syllables(word: String): Float {
        var s = 0f
        val digits = word.count { it.isDigit() }
        if (digits > 0) s += digits * 1.3f
        if (word.any { it in "£$€%" }) s += 1.5f
        val letters = word.filter { it.isLetter() }
        if (letters.isNotEmpty()) {
            if (letters.length in 2..5 && letters.all { it.isUpperCase() }) {
                s += letters.length.toFloat() // an abbreviation, spelled out: N-H-S
            } else {
                val lower = letters.lowercase()
                var groups = 0
                var inVowel = false
                for (ch in lower) {
                    val v = ch in "aeiouy"
                    if (v && !inVowel) groups++
                    inVowel = v
                }
                if (lower.endsWith("e") && groups > 1 && !lower.endsWith("le")) groups--
                s += groups.coerceAtLeast(1)
            }
        }
        return s.coerceAtLeast(0.5f) + 0.3f // a little time between words
    }
}
