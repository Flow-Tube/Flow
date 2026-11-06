package io.github.aedev.flow.data.lyrics

import kotlin.math.abs

/**
 * How a lyrics search names a song, and how a provider's candidates are checked against it.
 * Credits and packaging ("feat. X", "Official Video") are dropped from the search. Version tags
 * ("Live", "Remix", "Sped Up") name a different recording, so they stay, and a candidate has to
 * carry the same ones: otherwise a live cut gets the studio version's timing.
 *
 * The version and scoring rules follow LastWave-Native (GPL-3.0).
 */
internal object LyricsMatching {
    data class Song(
        val title: String,
        val artist: String,
        val durationSeconds: Int,
    )

    /**
     * Exact title and artist agreement. A fuzzy title passes only with a close duration too. A
     * candidate with a different title, or a different artist when one is asked for, scores 0.
     */
    const val MIN_SCORE = 5

    private const val EXACT_TITLE = 3
    private const val FUZZY_TITLE = 1
    private const val ARTIST = 2
    private const val CLOSE_DURATION = 3
    private const val NEAR_DURATION = 1
    private const val CLOSE_DURATION_SECONDS = 3
    private const val NEAR_DURATION_SECONDS = 10

    private val FEAT_BRACKET = Regex("""(?i)\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""")
    private val FEAT_TRAILING = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$""")
    private val PACKAGING_BRACKET =
        Regex(
            """(?i)\s*[(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|clip|full\s*song|official)\s*[)\]]""",
        )
    private val PACKAGING_SUFFIX =
        Regex("""(?i)\s*[-|]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visuali[sz]er|lyrics?\s*video|lyrics?)\s*$""")
    private val BRACKETED = Regex("""[(\[](.*?)[)\]]""")
    private val DASH_SUFFIX = Regex("""\s+[-–—]\s+([^-–—(\[]+)$""")
    private val ARTIST_SPLIT = Regex("""(?i)\s*(?:,|、|&|/|\bx\b|\band\b|\bvs\.?|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*""")
    private val PRIMARY_ARTIST_SPLIT = Regex("""(?i)\s+(feat\.?|ft\.?|featuring|&|,|vs\.?)\s+""")
    private val TOPIC_SUFFIX = Regex("""(?i)\s*-\s*Topic""")
    private val APOSTROPHES = Regex("""['’`]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    private val VERSION_KEYWORDS =
        listOf(
            "live" to "live",
            "concert" to "live",
            "session" to "live",
            "unplugged" to "unplugged",
            "acoustic" to "acoustic",
            "remix" to "remix",
            "cover" to "cover",
            "karaoke" to "karaoke",
            "instrumental" to "instrumental",
            "slowed" to "slowed",
            "sped up" to "sped",
            "speed up" to "sped",
            "spedup" to "sped",
            "nightcore" to "sped",
            "demo" to "demo",
            "8d" to "8d",
        ).map { (keyword, tag) -> Regex("""(?i)\b${Regex.escape(keyword)}\b""") to tag }

    fun cleanTitle(raw: String): String =
        raw
            .replace(FEAT_BRACKET, "")
            .replace(FEAT_TRAILING, "")
            .replace(PACKAGING_BRACKET, "")
            .replace(PACKAGING_SUFFIX, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { raw.trim() }

    /** The first credited artist, which is the one every catalogue files the song under. */
    fun cleanArtist(raw: String): String =
        raw
            .replace(TOPIC_SUFFIX, "")
            .split(PRIMARY_ARTIST_SPLIT)[0]
            .trim()

    fun versionTags(title: String): Set<String> {
        val segments = BRACKETED.findAll(title).map { it.groupValues[1] } + listOfNotNull(DASH_SUFFIX.find(title)?.groupValues?.get(1))
        return segments
            .flatMap { segment -> VERSION_KEYWORDS.filter { (pattern, _) -> pattern.containsMatchIn(segment) }.map { it.second } }
            .toSet()
    }

    fun sameVersion(
        requestTitle: String,
        candidateTitle: String,
    ): Boolean = versionTags(requestTitle) == versionTags(candidateTitle)

    fun score(
        request: Song,
        candidate: Song,
    ): Int {
        var score = titleScore(candidate.title, request.title)
        if (score == 0) return 0
        if (artistMatches(candidate.artist, request.artist)) {
            score += ARTIST
        } else if (request.artist.isNotBlank()) {
            return 0
        }
        if (request.durationSeconds > 0 && candidate.durationSeconds > 0) {
            score +=
                when (abs(request.durationSeconds - candidate.durationSeconds)) {
                    in 0..CLOSE_DURATION_SECONDS -> CLOSE_DURATION
                    in 0..NEAR_DURATION_SECONDS -> NEAR_DURATION
                    else -> 0
                }
        }
        return score
    }

    /**
     * The candidate naming the same recording: same version tags, a score at or above
     * [MIN_SCORE], then the highest score, then [preferred] ones, then the closest duration.
     */
    fun <T> best(
        candidates: List<T>,
        request: Song,
        describe: (T) -> Song,
        preferred: (T) -> Boolean = { false },
    ): T? = ranked(candidates, request, describe, preferred).firstOrNull()

    /** Every candidate [best] would accept, best first. */
    fun <T> ranked(
        candidates: List<T>,
        request: Song,
        describe: (T) -> Song,
        preferred: (T) -> Boolean = { false },
    ): List<T> =
        candidates
            .map { it to describe(it) }
            .filter { (_, song) -> sameVersion(request.title, song.title) }
            .map { (candidate, song) -> Triple(candidate, song, score(request, song)) }
            .filter { (_, _, score) -> score >= MIN_SCORE }
            .sortedWith(
                compareByDescending<Triple<T, Song, Int>> { it.third }
                    .thenByDescending { preferred(it.first) }
                    .thenBy { durationGap(request, it.second) },
            ).map { it.first }

    private fun durationGap(
        request: Song,
        candidate: Song,
    ): Int =
        if (request.durationSeconds > 0 && candidate.durationSeconds > 0) {
            abs(request.durationSeconds - candidate.durationSeconds)
        } else {
            Int.MAX_VALUE
        }

    private fun titleScore(
        candidate: String,
        request: String,
    ): Int {
        val a = normalize(baseTitle(candidate))
        val b = normalize(baseTitle(request))
        return when {
            a.isEmpty() || b.isEmpty() -> 0
            a == b -> EXACT_TITLE
            similar(a, b, minRatio = 0.7) -> FUZZY_TITLE
            else -> 0
        }
    }

    private fun artistMatches(
        candidate: String,
        request: String,
    ): Boolean {
        val wanted = credits(request.replace(TOPIC_SUFFIX, ""))
        val credited = credits(candidate)
        return wanted.any { name -> credited.any { it == name || similar(it, name, minRatio = 0.6) } }
    }

    /** The whole name comes too: a comma can be part of one, as in "Tyler, The Creator". */
    private fun credits(artist: String): List<String> =
        (listOf(artist) + artist.split(ARTIST_SPLIT)).map(::normalize).filter { it.isNotEmpty() }

    /** The title without its brackets and trailing "- …" part, once versions are known to agree. */
    private fun baseTitle(title: String): String = cleanTitle(title).replace(BRACKETED, " ").replace(DASH_SUFFIX, "")

    private fun normalize(text: String): String =
        text
            .lowercase()
            .replace(APOSTROPHES, "")
            .replace(NON_WORD, " ")
            .trim()

    private fun similar(
        a: String,
        b: String,
        minRatio: Double,
    ): Boolean {
        if (a.contains(b) || b.contains(a)) {
            val ratio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
            if (ratio >= minRatio) return true
        }
        val aTokens = a.split(' ').toSet()
        val bTokens = b.split(' ').toSet()
        return aTokens.intersect(bTokens).size.toDouble() / aTokens.union(bTokens).size >= 0.5
    }
}
