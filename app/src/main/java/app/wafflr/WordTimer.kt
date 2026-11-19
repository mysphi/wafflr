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

        // Use the longest pauses as clause boundaries, if there are enough of them.
        val anchors: List<IntRange> = if (breaks > 0 && pauses.size >= breaks) {
            pauses.sortedByDescending { it.last - it.first }.take(breaks).sortedBy { it.first }
        } else emptyList()

        val starts = IntArray(words.size)
        fun spread(clause: List<Int>, fromWin: Int, toWin: Int) {
            val weights = clause.map { syllables(matches[it].value) }
            val total = weights.sum().coerceAtLeast(0.001f)
            var t = fromWin.toFloat()
            val span = (toWin - fromWin).coerceAtLeast(1).toFloat()
            for ((k, idx) in clause.withIndex()) {
                starts[idx] = (t * win).toInt()
                t += span * weights[k] / total
            }
        }
        if (anchors.isNotEmpty()) {
            var from = first
            for ((c, clause) in clauses.withIndex()) {
                val to = if (c < anchors.size) anchors[c].first else last + 1
                spread(clause, from, to)
                if (c < anchors.size) from = anchors[c].last + 1
            }
        } else {
            spread(matches.indices.toList(), first, last + 1)
        }
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
