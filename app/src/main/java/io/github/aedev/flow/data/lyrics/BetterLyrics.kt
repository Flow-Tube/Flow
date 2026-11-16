// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package io.github.aedev.flow.data.lyrics

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Service for fetching lyrics from the BetterLyrics API (lyrics-api.boidu.dev).
 * Returns structured LyricsEntry objects with word-level timestamps from TTML.
 */
class BetterLyrics(
    private val client: OkHttpClient,
) {
    private val gson = Gson()

    private data class TTMLResponse(
        @SerializedName("ttml") val ttml: String? = null,
    )

    /**
     * Fetch lyrics by title, artist, and duration.
     * Uses TTMLParser.parseTTMLToLines() directly to preserve word-level data.
     *
     * @param title Song title
     * @param artist Artist name
     * @param duration Duration in seconds
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val encodedArtist = URLEncoder.encode(artist, "UTF-8")

                val url =
                    buildString {
                        append("$BASE_URL/getLyrics")
                        append("?s=$encodedTitle")
                        append("&a=$encodedArtist")
                        if (!album.isNullOrBlank()) {
                            append("&al=${URLEncoder.encode(album, "UTF-8")}")
                        }
                        if (duration > 0) {
                            append("&d=$duration")
                        }
                    }

                Log.d(TAG, "Fetching: $url")

                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
                        ).header("Accept", "application/json")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Songs nobody has looked up yet need an API key (401). That is a miss for
                        // this song, not a refused client, so it must not bench the provider.
                        val uncached = response.code == 401 && response.body.string().contains("Uncached", ignoreCase = true)
                        val failure =
                            if (uncached || response.code == 404) {
                                LyricsNotFoundException("BetterLyrics has no lyrics for this song")
                            } else {
                                Exception("BetterLyrics API returned ${response.code}")
                            }
                        return@withContext Result.failure(failure)
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("Empty response from BetterLyrics"))
                    }

                    val ttml =
                        try {
                            val ttmlResponse = gson.fromJson(body, TTMLResponse::class.java)
                            ttmlResponse?.ttml
                        } catch (e: Exception) {
                            Log.d(TAG, "JSON parse failed, trying raw TTML")
                            if (body.contains("<tt") || body.contains("<p ")) body else null
                        }

                    if (ttml.isNullOrBlank()) {
                        return@withContext Result.failure(LyricsNotFoundException("No TTML in BetterLyrics response"))
                    }

                    val ttmlLines =
                        try {
                            TTMLParser.parseTTMLToLyricsEntries(ttml)
                        } catch (e: Exception) {
                            Log.w(TAG, "TTML parse failed: ${e.message}")
                            emptyList()
                        }

                    if (ttmlLines.isNotEmpty()) {
                        Log.d(TAG, "Parsed ${ttmlLines.size} lines, ${ttmlLines.count { it.words != null }} with word sync")
                        return@withContext Result.success(ttmlLines)
                    }

                    Result.failure(Exception("Failed to parse BetterLyrics TTML"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "BetterLyrics fetch failed: ${e.message}")
                Result.failure(e)
            }
        }

    private companion object {
        const val TAG = "BetterLyrics"
        const val BASE_URL = "https://lyrics-api.boidu.dev"
    }
}
