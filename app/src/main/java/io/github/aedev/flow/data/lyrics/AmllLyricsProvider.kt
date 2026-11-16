package io.github.aedev.flow.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The AMLL lyrics database: word-synced TTML that people contribute, behind an open API with no
 * key. Its catalogue is small, so it sits near the end of the order, and each entry lists every
 * name its song goes by, any of which can match the request.
 */
class AmllLyricsProvider(
    private val client: OkHttpClient,
) : LyricsProvider {
    override val name = NAME

    @Serializable
    internal data class Item(
        val id: Long,
        val musicNames: List<String> = emptyList(),
        val artistNames: List<String> = emptyList(),
    )

    @Serializable
    private data class SearchResponse(
        val data: SearchData? = null,
    )

    @Serializable
    private data class SearchData(
        val items: List<Item> = emptyList(),
    )

    @Serializable
    private data class LyricResponse(
        val data: LyricData? = null,
    )

    @Serializable
    private data class LyricData(
        val lyrics: String? = null,
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
                require(title.isNotBlank() && artist.isNotBlank()) { "AMLL needs a title and an artist" }
                val request = LyricsMatching.Song(title, artist, duration)
                val cleanArtist = LyricsMatching.cleanArtist(artist)
                val url =
                    "$BASE_URL/search"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("musicName", LyricsMatching.cleanTitle(title))
                        .addQueryParameter("artistName", cleanArtist)
                        .build()
                val items =
                    json
                        .decodeFromString<SearchResponse>(get(url.toString()))
                        .data
                        ?.items
                        .orEmpty()
                val item = pick(items, request) ?: throw LyricsNotFoundException("No AMLL match")
                val document = lyric(item.id) ?: throw LyricsNotFoundException("AMLL entry is empty")
                TTMLParser.parseTTMLToLyricsEntries(document).ifEmpty { throw LyricsNotFoundException("AMLL entry has no lines") }
            }
        }

    private fun lyric(id: Long): String? {
        val url =
            "$BASE_URL/get"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "id",
                    id.toString(),
                ).addQueryParameter("format", "ttml")
                .build()
        return json
            .decodeFromString<LyricResponse>(get(url.toString()))
            .data
            ?.lyrics
            ?.takeIf { it.isNotBlank() }
    }

    private fun get(url: String): String =
        client
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build(),
            ).execute()
            .use { response ->
                when {
                    response.isSuccessful -> response.body.string()
                    response.code == NOT_FOUND -> throw LyricsNotFoundException("No AMLL match")
                    else -> throw IOException("AMLL returned ${response.code}")
                }
            }

    internal companion object {
        const val NAME = "AMLL"
        private const val BASE_URL = "https://api.amll.dev/v1/lyrics"
        private const val NOT_FOUND = 404
        private val json = Json { ignoreUnknownKeys = true }

        /** The entry naming the requested song under any of its names. */
        fun pick(
            items: List<Item>,
            request: LyricsMatching.Song,
        ): Item? {
            val names = items.flatMap { item -> item.musicNames.map { name -> item to name } }
            return LyricsMatching
                .best(names, request, describe = { (item, name) ->
                    LyricsMatching.Song(name, item.artistNames.joinToString(", "), 0)
                })
                ?.first
        }
    }
}
