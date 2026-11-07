package io.github.aedev.flow.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * NetEase Cloud Music's public song search and lyric lookup, which need no account. Its catalogue
 * reaches well beyond Chinese music, in line-synced LRC. A song can hold only its credits as timed
 * lines, and that is no lyric at all, so an answer has to carry real lines to count.
 */
class NetEaseLyricsProvider(
    private val client: OkHttpClient,
) : LyricsProvider {
    override val name = NAME

    @Serializable
    private data class SearchResponse(
        val result: SearchResult? = null,
    )

    @Serializable
    private data class SearchResult(
        val songs: List<Song> = emptyList(),
    )

    @Serializable
    private data class Song(
        val id: Long,
        val name: String = "",
        val ar: List<Artist> = emptyList(),
        val dt: Long = 0,
    )

    @Serializable
    private data class Artist(
        val name: String = "",
    )

    @Serializable
    private data class LyricResponse(
        val lrc: Lrc? = null,
    )

    @Serializable
    private data class Lrc(
        val lyric: String? = null,
    )

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = LyricsMatching.Song(title, artist, duration)
                val songs = search("$title ${LyricsMatching.cleanArtist(artist)}".trim())
                val candidates =
                    LyricsMatching.ranked(songs, request, describe = { song ->
                        LyricsMatching.Song(song.name, song.ar.joinToString(", ") { it.name }, (song.dt / MILLIS_PER_SECOND).toInt())
                    })
                candidates
                    .take(MAX_LOOKUPS)
                    .firstNotNullOfOrNull { song -> parseLyric(lyric(song.id)).ifEmpty { null } }
                    ?: throw LyricsNotFoundException("No NetEase lyrics")
            }
        }

    private fun search(query: String): List<Song> {
        val form =
            FormBody
                .Builder()
                .add("s", query)
                .add("type", "1")
                .add("limit", SEARCH_RESULTS)
                .build()
        val body = call(Request.Builder().url(SEARCH_URL).post(form))
        return json
            .decodeFromString<SearchResponse>(body)
            .result
            ?.songs
            .orEmpty()
    }

    private fun lyric(songId: Long): String {
        val url =
            LYRIC_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("id", songId.toString())
                .addQueryParameter("lv", "-1")
                .build()
        return json
            .decodeFromString<LyricResponse>(call(Request.Builder().url(url)))
            .lrc
            ?.lyric
            .orEmpty()
    }

    private fun call(request: Request.Builder): String =
        client.newCall(request.header("Referer", REFERER).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NetEase returned ${response.code}")
            response.body.string()
        }

    internal companion object {
        const val NAME = "NetEase"
        private const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
        private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
        private const val REFERER = "https://music.163.com/"
        private const val SEARCH_RESULTS = "10"
        private const val MAX_LOOKUPS = 2
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_REAL_LINES = 6
        private val json = Json { ignoreUnknownKeys = true }

        /** NetEase writes some stamps as `[00:00.00-1]`, which no LRC reader expects. */
        private val STAMP_SUFFIX = Regex("""(\[\d+:\d+[.:]\d+)-\d+]""")
        private val CREDIT_LINE =
            Regex(
                """^(?:\[[^]]*])+\s*(?:作词|作曲|编曲|制作|制作人|监制|混音|母带|录音|和声|吉他|贝斯|鼓|弦乐|人声|出品|发行|词|曲|lyrics? by|written by|composed by|lyricist|composer|producer|arranger)\s*[:：].*$""",
                RegexOption.IGNORE_CASE,
            )

        /** The timed lines of [lrc] without its credits, or nothing when too few real lines remain. */
        fun parseLyric(lrc: String): List<LyricsEntry> {
            val lines =
                lrc
                    .replace(STAMP_SUFFIX, "$1]")
                    .lines()
                    .filterNot { CREDIT_LINE.matches(it.trim()) }
                    .joinToString("\n")
            val entries = LyricsUtils.parseLyrics(lines)
            return entries.takeIf { found -> found.count { it.text.isNotBlank() } >= MIN_REAL_LINES }.orEmpty()
        }
    }
}
