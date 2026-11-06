package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Unison: lyrics people submit themselves, so its catalogue is small and uneven, but it sometimes
 * has a song the licensed sources lack. Each entry says whether it is Apple-style TTML, LRC (word
 * stamps included) or plain text, and that picks the parser.
 */
class UnisonLyricsProvider(
    private val client: OkHttpClient,
) : LyricsProvider {
    override val name = NAME

    @Serializable
    private data class Response(
        val success: Boolean? = null,
        val data: Entry? = null,
    )

    @Serializable
    private data class Entry(
        val lyrics: String? = null,
        val format: String? = null,
        val syncType: String? = null,
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
                require(title.isNotBlank() && artist.isNotBlank()) { "Unison needs a title and an artist" }
                val url =
                    LYRICS_URL
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("song", title)
                        .addQueryParameter("artist", LyricsMatching.cleanArtist(artist))
                        .apply {
                            if (!album.isNullOrBlank()) addQueryParameter("album", album)
                            if (duration > 0) addQueryParameter("duration", duration.toString())
                        }.build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", "Flow/${BuildConfig.VERSION_NAME}")
                        .header("Accept", "application/json")
                        .build()
                val body =
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> response.body.string()
                            response.code == 404 -> null
                            else -> throw IOException("Unison returned ${response.code}")
                        }
                    }
                body?.let(::parse)?.ifEmpty { null } ?: throw LyricsNotFoundException("No Unison lyrics")
            }
        }

    internal companion object {
        const val NAME = "Unison"
        private const val LYRICS_URL = "https://unison.boidu.dev/lyrics"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<LyricsEntry> {
            val response = json.decodeFromString<Response>(body)
            val entry = response.data?.takeIf { response.success == true } ?: return emptyList()
            val text = entry.lyrics?.takeIf { it.isNotBlank() } ?: return emptyList()
            return when {
                entry.format.equals("ttml", ignoreCase = true) -> TTMLParser.parseTTMLToLyricsEntries(text)
                entry.syncType.equals("plain", ignoreCase = true) -> listOf(LyricsEntry(time = 0L, text = text.trim()))
                else -> LyricsUtils.parseLyrics(text)
            }
        }
    }
}
