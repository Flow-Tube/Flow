// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics.paxsenix

import android.util.Log
import com.google.gson.Gson
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.lyrics.LyricsMatching
import io.github.aedev.flow.data.lyrics.LyricsNotFoundException
import io.github.aedev.flow.data.lyrics.LyricsSourceBusyException
import io.github.aedev.flow.data.lyrics.TTMLParser
import io.github.aedev.flow.data.lyrics.paxsenix.models.AppleMusicSearchResponse
import io.github.aedev.flow.data.lyrics.paxsenix.models.LyricsResponse
import io.github.aedev.flow.data.lyrics.paxsenix.models.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

class Paxsenix(
    private val client: OkHttpClient,
) {
    private val gson = Gson()

    private val tokenManager = AppleTokenManager(client)

    private val titleCleanupPatterns =
        listOf(
            Regex("""\s*\(.*?($TITLE_NOISE_WORDS).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\[.*?($TITLE_NOISE_WORDS).*?\]""", RegexOption.IGNORE_CASE),
            Regex("""\s*【.*?】"""),
            Regex("""\s*\|.*$"""),
            Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
        )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> =
        try {
            Result.success(findLyrics(title, artist, duration, album))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun findLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): String {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        val searchQueries =
            buildList {
                add("$cleanedTitle $cleanedArtist")
                add(cleanedTitle)
                if (!album.isNullOrBlank()) add("$cleanedTitle $cleanedArtist $album")
            }

        var allResults: List<Pair<SearchResult, Double>> = emptyList()
        var couldNotAsk = false

        for (query in searchQueries) {
            if (allResults.isEmpty()) {
                val searchResults = search(query) { couldNotAsk = true }
                if (searchResults.isNotEmpty()) {
                    allResults = scoreAndFilterResults(searchResults, title, artist, duration)
                }
            }
        }

        if (allResults.isEmpty()) {
            throw if (couldNotAsk) IOException("Paxsenix search failed") else LyricsNotFoundException("No tracks found on Paxsenix")
        }

        var bestLyrics: String? = null
        var bestQuality = 0
        // Every candidate is asked of the same server, so one that is down or busy ends the song.
        var busy: LyricsSourceBusyException? = null

        for ((result, _) in allResults.take(10)) {
            currentCoroutineContext().ensureActive()
            val lrc =
                try {
                    fetchLyricsForTrack(result.id) { couldNotAsk = true }
                } catch (e: LyricsSourceBusyException) {
                    busy = e
                    break
                } ?: continue
            if (lrc.isEmpty()) continue

            val quality = getQuality(lrc)
            if (quality > bestQuality) {
                bestQuality = quality
                bestLyrics = lrc
            }
            if (bestQuality == 3) break
        }

        return bestLyrics
            ?: throw (busy ?: if (couldNotAsk) IOException("Paxsenix lyrics failed") else LyricsNotFoundException())
    }

    private suspend fun search(
        query: String,
        onCouldNotAsk: () -> Unit,
    ): List<SearchResult> {
        currentCoroutineContext().ensureActive()
        // The public iTunes Search needs no token and finds the same Apple Music ids; the token
        // scraped from Apple's web player is the fallback.
        runCatching { searchItunes(query) }
            .onFailure {
                Log.w(TAG, "iTunes search error: ${it.message}")
                onCouldNotAsk()
            }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return try {
            val token = tokenManager.getToken() ?: return emptyList()
            val results = searchWithToken(token, query)
            if (results == null) {
                tokenManager.clearToken()
                val newToken = tokenManager.getToken() ?: return emptyList()
                searchWithToken(newToken, query) ?: emptyList()
            } else {
                results
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search error: ${e.message}")
            emptyList()
        }
    }

    private fun searchItunes(query: String): List<SearchResult> {
        val url =
            ITUNES_SEARCH_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", "25")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        val body =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("iTunes search returned ${response.code}")
                response.body.string()
            }
        return parseItunesSearch(body)
    }

    private fun searchWithToken(
        token: String,
        query: String,
    ): List<SearchResult>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url =
            "$APPLE_MUSIC_API_BASE/search?term=$encodedQuery&types=songs&limit=25&l=en-US&platform=web" +
                "&format[resources]=map&include[songs]=artists&extend=artistUrl"

        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:95.0) Gecko/20100101 Firefox/95.0")
                .header("Accept", "application/json")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("x-apple-renewal", "true")
                .build()

        val response = client.newCall(request).execute()
        if (response.code == 401) {
            response.close()
            return null
        }
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val body = response.body?.string() ?: return emptyList()
        val parsed =
            try {
                gson.fromJson(body, AppleMusicSearchResponse::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse Apple Music search response: ${e.message}")
                return emptyList()
            }

        val songs = parsed.results.songs?.data ?: return emptyList()

        return songs.mapNotNull { songData ->
            val detail = parsed.resources?.songs?.get(songData.id) ?: return@mapNotNull null
            val attr = detail.attributes
            SearchResult(
                id = songData.id,
                trackName = attr.name,
                artistName = attr.artistName,
                albumName = attr.albumName,
                duration = attr.durationInMillis?.toInt()?.div(1000),
                artwork =
                    attr.artwork
                        ?.url
                        ?.replace("{w}", "100")
                        ?.replace("{h}", "100")
                        ?.replace("{f}", "png"),
            )
        }
    }

    private fun getQuality(lrc: String): Int {
        if (lrc.isBlank()) return 0
        val hasWordTimings =
            (lrc.contains("<") && lrc.contains(">") && (lrc.contains("|") || lrc.contains(":"))) ||
                lrc.contains(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>"))
        if (hasWordTimings) return 3
        val hasLineTimings =
            lrc.contains(Regex("\\[\\d\\d:\\d\\d\\.\\d{2,3}\\]")) ||
                lrc.contains(Regex("^\\[bg:.*\\]", RegexOption.MULTILINE))
        if (hasLineTimings) return 2
        return 1
    }

    private fun scoreAndFilterResults(
        results: List<SearchResult>,
        title: String,
        artist: String,
        duration: Int,
    ): List<Pair<SearchResult, Double>> {
        val cleanupRegex = Regex("""\s*\(.*?\)|\s*\[.*?\]""")
        val cleanedTitle = title.replace(cleanupRegex, "").lowercase().trim()
        val cleanedArtist = cleanArtist(artist).lowercase()
        val targetIsMixed = title.contains("mixed", ignoreCase = true)
        val targetIsRemix = title.contains("remix", ignoreCase = true)

        return results
            .filter { LyricsMatching.sameVersion(title, it.displayName) }
            .map { result ->
                var score = 0.0
                val resultTitle = result.displayName
                val resultArtist = result.displayArtist

                result.duration?.takeIf { duration > 0 }?.let { d ->
                    val diff = abs(d - duration)
                    when {
                        diff <= 2 -> score += 100
                        diff <= 5 -> score += 50
                        diff <= 10 -> score += 10
                        else -> score -= 50
                    }
                }

                val resultTitleCleaned = resultTitle.replace(cleanupRegex, "").lowercase().trim()
                when {
                    resultTitleCleaned == cleanedTitle -> score += 80
                    resultTitleCleaned.contains(cleanedTitle) || cleanedTitle.contains(resultTitleCleaned) -> score += 40
                }

                val resultIsMixed = resultTitle.contains("mixed", ignoreCase = true)
                val resultIsRemix = resultTitle.contains("remix", ignoreCase = true)
                if (resultIsMixed && !targetIsMixed) score -= 60
                if (resultIsRemix && !targetIsRemix) score -= 40

                val resultArtistLower = resultArtist.lowercase()
                when {
                    resultArtistLower.contains(cleanedArtist) -> {
                        score += 50
                    }

                    else -> {
                        val artistWords = cleanedArtist.split(Regex("\\s+")).filter { it.length > 2 }
                        if (artistWords.any { resultArtistLower.contains(it) }) score += 25
                    }
                }

                result to score
            }.sortedByDescending { it.second }
            .filter { it.second > 0 }
            .take(10)
    }

    /** Null when Paxsenix has nothing for [id]; a [LyricsSourceBusyException] when it could not answer. */
    private fun fetchLyricsForTrack(
        id: String,
        onCouldNotAsk: () -> Unit,
    ): String? {
        val request =
            Request
                .Builder()
                .url("$PAXSENIX_BASE/apple-music/lyrics?id=$id")
                .header("User-Agent", "Flow/${BuildConfig.VERSION_NAME}")
                .build()
        val body =
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) return null
                    if (!response.isSuccessful) {
                        throw LyricsSourceBusyException("busy, HTTP ${response.code}", retryAfterMs(response.header("Retry-After")))
                    }
                    response.body.string()
                }
            } catch (e: IOException) {
                throw LyricsSourceBusyException("busy, ${e.message}")
            }
        return try {
            lyricsFrom(body)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable lyrics for track $id: ${e.message}")
            onCouldNotAsk()
            null
        }
    }

    private fun lyricsFrom(body: String): String? {
        val lyricsResponse = gson.fromJson(body, LyricsResponse::class.java)

        if (!lyricsResponse.ttmlContent.isNullOrBlank()) {
            val lrc = convertTTMLToLRC(lyricsResponse.ttmlContent)
            if (lrc.isNotEmpty()) return lrc
        }

        if (!lyricsResponse.elrcMultiPerson.isNullOrBlank()) return lyricsResponse.elrcMultiPerson
        if (!lyricsResponse.elrc.isNullOrBlank()) return lyricsResponse.elrc
        if (!lyricsResponse.plain.isNullOrBlank()) return lyricsResponse.plain

        if (lyricsResponse.content.isEmpty()) return null

        val hasWordLevel = lyricsResponse.type == "Syllable"
        if (!hasWordLevel) {
            return lyricsResponse.content
                .map { line -> line.text.joinToString(" ") { it.text } }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        return buildString {
            lyricsResponse.content.forEach { line ->
                val timeMs = line.timestamp
                val minutes = timeMs / 1000 / 60
                val seconds = (timeMs / 1000) % 60
                val centiseconds = (timeMs % 1000) / 10

                val agent =
                    when {
                        line.background -> "{bg}"
                        line.oppositeTurn -> "{agent:v2}"
                        else -> "{agent:v1}"
                    }

                val lineText = line.text.joinToString(" ") { it.text }

                if (lineText.isNotBlank()) {
                    appendLine(String.format(Locale.US, "[%02d:%02d.%02d]%s%s", minutes, seconds, centiseconds, agent, lineText))

                    if (line.text.isNotEmpty()) {
                        val wordsData =
                            line.text.joinToString("|") { word ->
                                "${word.text}:${word.timestamp.toDouble() / 1000}:${word.endtime.toDouble() / 1000}"
                            }
                        if (wordsData.isNotEmpty()) appendLine("<$wordsData>")
                    }
                }
            }
        }
    }

    private fun convertTTMLToLRC(ttml: String): String {
        return try {
            val entries = TTMLParser.parseTTMLToLyricsEntries(ttml)
            if (entries.isEmpty()) return ""
            entries.joinToString("\n") { entry ->
                val minutes = entry.time / 60000
                val seconds = (entry.time / 1000) % 60
                val millis = entry.time % 1000
                val agentPrefix =
                    when {
                        entry.isBackground -> "{bg}"
                        entry.agent != null -> "{agent:${entry.agent}}"
                        else -> ""
                    }
                val wordBlock =
                    entry.words?.joinToString("|") { w ->
                        "${w.text}:${w.startTime.toDouble() / 1000}:${w.endTime.toDouble() / 1000}"
                    }
                val mainLine = "[%02d:%02d.%03d]%s%s".format(minutes, seconds, millis, agentPrefix, entry.text)
                if (wordBlock != null) "$mainLine\n<$wordBlock>" else mainLine
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTML conversion failed: ${e.message}")
            ""
        }
    }

    private class AppleTokenManager(
        private val httpClient: OkHttpClient,
    ) {
        @Volatile
        private var cachedToken: String? = null
        private val mutex = Mutex()

        suspend fun getToken(): String? =
            mutex.withLock {
                cachedToken?.let { return it }
                try {
                    val mainRequest =
                        Request
                            .Builder()
                            .url("https://beta.music.apple.com")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .build()
                    val mainBody = httpClient.newCall(mainRequest).execute().use { it.body?.string() ?: "" }

                    val indexJsRegex = Regex("""/assets/index~[^/]+\.js""")
                    val indexJsMatch =
                        indexJsRegex.find(mainBody)
                            ?: throw Exception("Could not find index JS URL")

                    val jsRequest =
                        Request
                            .Builder()
                            .url("https://beta.music.apple.com${indexJsMatch.value}")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .build()
                    val jsBody = httpClient.newCall(jsRequest).execute().use { it.body?.string() ?: "" }

                    val tokenRegex = Regex("""eyJh([^"]*)""")
                    val tokenMatch =
                        tokenRegex.find(jsBody)
                            ?: throw Exception("Could not find token")

                    val token = tokenMatch.value
                    cachedToken = token
                    token
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching Apple Music token: ${e.message}")
                    null
                }
            }

        fun clearToken() {
            cachedToken = null
        }
    }

    @Serializable
    private data class ItunesSearch(
        val results: List<ItunesSong> = emptyList(),
    )

    @Serializable
    private data class ItunesSong(
        val trackId: Long = 0,
        val trackName: String? = null,
        val artistName: String? = null,
        val collectionName: String? = null,
        val trackTimeMillis: Long? = null,
    )

    internal companion object {
        private const val TAG = "Paxsenix"
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private val json = Json { ignoreUnknownKeys = true }

        private fun retryAfterMs(header: String?): Long? =
            header
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.times(1000)

        fun parseItunesSearch(body: String): List<SearchResult> =
            json
                .decodeFromString<ItunesSearch>(body)
                .results
                .filter { it.trackId > 0 && !it.trackName.isNullOrBlank() }
                .map { song ->
                    SearchResult(
                        id = song.trackId.toString(),
                        trackName = song.trackName,
                        artistName = song.artistName,
                        albumName = song.collectionName,
                        duration = song.trackTimeMillis?.div(1000)?.toInt(),
                    )
                }

        private const val PAXSENIX_BASE = "https://lyrics.paxsenix.org"
        private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"
        private const val TITLE_NOISE_WORDS =
            "official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit"
    }
}
