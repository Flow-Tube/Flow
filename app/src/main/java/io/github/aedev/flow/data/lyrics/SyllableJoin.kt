package io.github.aedev.flow.data.lyrics

/**
 * SimpMusic's word-synced text writes the syllables of one word apart ("de vil's", "mid night"),
 * while the same song's line lyrics spell the words whole. Neighbouring parts of a line are joined
 * when the joined word is in [reference] and the parts never stand apart there, so "a way" joins
 * only for a song that sings "away" and never "a way". Each part keeps its own timing.
 */
internal object SyllableJoin {
    private const val MAX_PARTS = 5
    private val TIME_TAG = Regex("""[\[<]\d{1,2}:\d{2}[.:]\d{2,3}[\]>]""")
    private val SPACE = Regex("""\s+""")

    fun rejoin(
        entries: List<LyricsEntry>,
        reference: String,
    ): List<LyricsEntry> {
        val referenceKeys =
            LyricsUtils
                .decodeHtmlEntities(reference)
                .replace(TIME_TAG, " ")
                .split(SPACE)
                .map(::key)
                .filter { it.isNotEmpty() }
        if (referenceKeys.isEmpty()) return entries
        val words = referenceKeys.toHashSet()
        return entries.map { entry ->
            val joined = rejoinLine(entry.text, words, referenceKeys)
            if (joined == entry.text) entry else entry.copy(text = joined)
        }
    }

    private fun rejoinLine(
        text: String,
        words: Set<String>,
        referenceKeys: List<String>,
    ): String {
        val parts = text.split(' ')
        if (parts.size < 2) return text
        val keys = parts.map(::key)
        val out = mutableListOf<String>()
        var start = 0
        while (start < parts.size) {
            val end = longestJoin(start, keys, words, referenceKeys)
            out += parts.subList(start, end + 1).joinToString("")
            start = end + 1
        }
        return out.joinToString(" ")
    }

    private fun longestJoin(
        start: Int,
        keys: List<String>,
        words: Set<String>,
        referenceKeys: List<String>,
    ): Int {
        if (keys[start].isEmpty()) return start
        val last = minOf(keys.lastIndex, start + MAX_PARTS - 1)
        for (end in last downTo start + 1) {
            val run = keys.subList(start, end + 1)
            if (run.any { it.isEmpty() }) continue
            if (run.joinToString("") in words && !containsRun(referenceKeys, run)) return end
        }
        return start
    }

    private fun containsRun(
        haystack: List<String>,
        run: List<String>,
    ): Boolean = (0..haystack.size - run.size).any { at -> run.indices.all { haystack[at + it] == run[it] } }

    private fun key(token: String): String =
        LyricsUtils
            .decodeHtmlEntities(token)
            .replace('\u2019', '\'')
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '\'' }
            .trim('\'')
}
