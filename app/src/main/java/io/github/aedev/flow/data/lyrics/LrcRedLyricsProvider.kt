package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * lrc.red: Apple Music word-synced TTML archived by ISRC. Its search names the recording behind
 * each hit, and the hit is checked against the request with [LyricsMatching] before its document
 * is fetched. `lyrics-api.binimum.org`, which LyricsPlus used to call first, now redirects here.
 */
class LrcRedLyricsProvider(
    private val client: OkHttpClient,
) : LyricsProvider {
    override val name = NAME

    @Serializable
    internal data class Hit(
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        val duration: Int? = null,
        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )

    @Serializable
    private data class SearchResponse(
        val results: List<Hit> = emptyList(),
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
                val searchArtist = LyricsMatching.cleanArtist(artist)
                val shaped =
                    buildList {
                        add("track" to title)
                        if (searchArtist.isNotBlank()) add("artist" to searchArtist)
                        if (!album.isNullOrBlank()) add("album" to album)
                        if (duration > 0) add("duration" to duration.toString())
                    }
                val hit =
                    pick(search(shaped), request)
                        ?: pick(search(listOf("q" to if (searchArtist.isBlank()) title else "$searchArtist - $title")), request)
                        ?: throw LyricsNotFoundException("No lrc.red match")
                val document = get(hit.lyricsUrl!!.toHttpUrl()) ?: throw LyricsNotFoundException("lrc.red document missing")
                TTMLParser.parseTTMLToLyricsEntries(document).ifEmpty { throw LyricsNotFoundException("lrc.red document has no lines") }
            }
        }

    private fun search(params: List<Pair<String, String>>): List<Hit> {
        val url =
            SEARCH_URL
                .toHttpUrl()
                .newBuilder()
                .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        return get(url)?.let(::parseSearch).orEmpty()
    }

    private fun get(url: HttpUrl): String? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "Flow/${BuildConfig.VERSION_NAME}")
                .build()
        return client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> response.body.string().takeIf { it.isNotBlank() }
                response.code == 404 -> null
                else -> throw IOException("lrc.red returned ${response.code}")
            }
        }
    }

    internal companion object {
        const val NAME = "LrcRed"
        private const val SEARCH_URL = "https://lrc.red/api/v1"
        private val json = Json { ignoreUnknownKeys = true }

        fun parseSearch(body: String): List<Hit> = json.decodeFromString<SearchResponse>(body).results

        /** The same recording, word-timed files first; documents are only fetched over https. */
        fun pick(
            hits: List<Hit>,
            request: LyricsMatching.Song,
        ): Hit? =
            LyricsMatching.best(
                hits.filter { it.lyricsUrl?.toHttpUrlOrNull()?.isHttps == true },
                request,
                describe = { LyricsMatching.Song(it.trackName.orEmpty(), it.artistName.orEmpty(), it.duration ?: 0) },
                preferred = { it.timingType.equals("word", ignoreCase = true) },
            )
    }
}
