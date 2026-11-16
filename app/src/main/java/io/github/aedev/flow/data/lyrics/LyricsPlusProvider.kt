package io.github.aedev.flow.data.lyrics

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * LyricsPlus provider, adapted from Metrolist.
 *
 * This source often has fuller word-level lyrics than LRCLib and can fill gaps
 * that BetterLyrics/SimpMusic miss.
 */
class LyricsPlusProvider(
    private val client: OkHttpClient,
) : LyricsProvider {
    override val name = "LyricsPlus"

    private val gson = Gson()

    private data class LyricsPlusResponse(
        val type: String? = null,
        val lyrics: List<LyricLine>? = null,
    )

    private data class LyricLine(
        val time: Long = 0,
        val duration: Long = 0,
        val text: String = "",
        val syllabus: List<LyricWord>? = null,
        val element: LineElement? = null,
    )

    private data class LineElement(
        val singer: String? = null,
    )

    private data class LyricWord(
        val time: Long = 0,
        val duration: Long = 0,
        val text: String = "",
        val isBackground: Boolean = false,
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
                fetchLyricsPlus(title, LyricsMatching.cleanArtist(artist), duration, album)
                    ?: throw LyricsNotFoundException("LyricsPlus lyrics unavailable")
            }
        }

    private fun fetchLyricsPlus(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): List<LyricsEntry>? {
        if (title.isBlank() || artist.isBlank()) return null

        val url =
            buildString {
                append(LYRICS_PLUS_BASE_URL)
                append("/v2/lyrics/get")
                append("?title=${title.urlEncoded()}")
                append("&artist=${artist.urlEncoded()}")
                if (duration > 0) append("&duration=$duration")
                if (!album.isNullOrBlank()) append("&album=${album.urlEncoded()}")
            }
        val body = executeGet(url) ?: return null
        return convertLyricsPlus(gson.fromJson(body, LyricsPlusResponse::class.java))
    }

    private fun convertLyricsPlus(response: LyricsPlusResponse?): List<LyricsEntry>? {
        val lines = response?.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val isWordSync = response.type.equals("Word", ignoreCase = true)
        val result = mutableListOf<LyricsEntry>()

        for (line in lines) {
            val allWords = line.syllabus.orEmpty()
            val mainWords = allWords.filter { !it.isBackground }
            val bgWords = allWords.filter { it.isBackground }

            val mainText =
                when {
                    isWordSync && mainWords.isNotEmpty() -> buildText(mainWords)
                    mainWords.isEmpty() && bgWords.isNotEmpty() -> ""
                    else -> line.text.trim()
                }

            if (mainText.isNotBlank()) {
                result +=
                    LyricsEntry(
                        time = line.time,
                        text = mainText,
                        words = if (isWordSync) mainWords.toWordTimestamps() else null,
                        agent = line.element?.singer,
                        isBackground = false,
                    )
            }

            if (bgWords.isNotEmpty()) {
                val bgText = buildText(bgWords)
                if (bgText.isNotBlank()) {
                    result +=
                        LyricsEntry(
                            time = bgWords.minOf { it.time },
                            text = bgText,
                            words = if (isWordSync) bgWords.toWordTimestamps() else null,
                            agent = "bg",
                            isBackground = true,
                        )
                }
            }
        }

        return result.sorted().takeIf { it.isNotEmpty() }
    }

    private fun List<LyricWord>.toWordTimestamps(): List<WordTimestamp>? =
        filter { it.text.isNotBlank() }
            .map {
                WordTimestamp(
                    text = it.text.trim(),
                    startTime = it.time,
                    endTime = it.time + it.duration.coerceAtLeast(1),
                )
            }.takeIf { it.isNotEmpty() }

    private fun buildText(words: List<LyricWord>): String = words.joinToString("") { it.text }.trim()

    private fun executeGet(url: String): String? {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,text/plain,*/*")
                .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LyricsPlus returned ${response.code}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        // The other public mirrors (atomix.one, prjktla.my.id, lyricsplus-seven.vercel.app) were
        // all down on 2026-10-19: no connection, 502 and DEPLOYMENT_DISABLED.
        private const val LYRICS_PLUS_BASE_URL = "https://lyricsplus.binimum.org"
    }
}
