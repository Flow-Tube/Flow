package io.github.aedev.flow.data.lyrics

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * LyricsProvider implementation wrapping the LrcLib API.
 * LrcLib provides standard LRC synced lyrics (line-level only, no word sync).
 */
class LrcLibLyricsProvider(
    client: OkHttpClient,
) : LyricsProvider {
    override val name = "LrcLib"

    private val client =
        client
            .newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    private val gson = Gson()

    internal data class SearchResult(
        val trackName: String? = null,
        val artistName: String? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val duration: Double? = null,
    )

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val result = tryFetch(artist, title, duration) ?: trySearch(artist, title, duration)

                if (result != null) {
                    val syncedLrc = result.syncedLyrics
                    val plainLrc = result.plainLyrics

                    if (syncedLrc != null) {
                        val entries = LyricsUtils.parseLyrics(syncedLrc)
                        if (entries.isNotEmpty()) {
                            return@withContext Result.success(entries)
                        }
                    }

                    if (plainLrc != null) {
                        // Plain text: single entry with no sync
                        val entries = listOf(LyricsEntry(time = 0L, text = plainLrc))
                        return@withContext Result.success(entries)
                    }

                    Result.failure(Exception("No lyrics content in response"))
                } else {
                    Result.failure(Exception("No lyrics found on LrcLib"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun tryFetch(
        artist: String,
        title: String,
        duration: Int,
    ): SearchResult? {
        try {
            if (artist.isEmpty()) return null

            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")

            val url =
                if (duration > 0) {
                    "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle&duration=$duration"
                } else {
                    "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle"
                }

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    return gson.fromJson(body, SearchResult::class.java)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LrcLibProvider", "Fetch failed", e)
        }
        return null
    }

    private fun trySearch(
        artist: String,
        title: String,
        duration: Int,
    ): SearchResult? {
        try {
            val q = if (artist.isNotEmpty()) "$artist $title" else title
            val encodedQ = URLEncoder.encode(q, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encodedQ"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val results = gson.fromJson(body, Array<SearchResult>::class.java)
                return pick(results.asList(), LyricsMatching.Song(title, artist, duration))
            }
        } catch (e: Exception) {
            android.util.Log.e("LrcLibProvider", "Search failed", e)
        }
        return null
    }

    internal companion object {
        private const val MAX_DURATION_GAP_SECONDS = 10

        /** A search returns whatever shares a word with the query, so only the same song may pass. */
        fun pick(
            results: List<SearchResult>,
            request: LyricsMatching.Song,
        ): SearchResult? {
            val matches =
                LyricsMatching.ranked(
                    results.filter { closeInLength(it, request) },
                    request,
                    describe = { LyricsMatching.Song(it.trackName.orEmpty(), it.artistName.orEmpty(), it.duration?.toInt() ?: 0) },
                )
            return matches.firstOrNull { it.syncedLyrics != null } ?: matches.firstOrNull()
        }

        private fun closeInLength(
            result: SearchResult,
            request: LyricsMatching.Song,
        ): Boolean {
            val length = result.duration ?: return true
            return request.durationSeconds <= 0 || abs(length - request.durationSeconds) <= MAX_DURATION_GAP_SECONDS
        }
    }
}
