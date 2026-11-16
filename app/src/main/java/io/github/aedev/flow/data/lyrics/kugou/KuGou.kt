// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics.kugou

import com.google.gson.Gson
import io.github.aedev.flow.data.lyrics.LyricsMatching
import io.github.aedev.flow.data.lyrics.LyricsNotFoundException
import io.github.aedev.flow.data.lyrics.kugou.models.DownloadLyricsResponse
import io.github.aedev.flow.data.lyrics.kugou.models.Keyword
import io.github.aedev.flow.data.lyrics.kugou.models.SearchLyricsResponse
import io.github.aedev.flow.data.lyrics.kugou.models.SearchSongResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.min

class KuGou(
    private val client: OkHttpClient,
) {
    private val gson = Gson()

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> =
        runCatching {
            val keyword = generateKeyword(title, LyricsMatching.cleanArtist(artist), album)
            val candidate =
                getLyricsCandidate(keyword, LyricsMatching.Song(title, artist, duration))
                    ?: throw LyricsNotFoundException("No lyrics candidate")
            val downloaded = downloadLyrics(candidate.id, candidate.accesskey)
            val decoded =
                android.util.Base64
                    .decode(downloaded.content, android.util.Base64.DEFAULT)
                    .decodeToString()
            decoded.normalize()
        }

    private suspend fun getLyricsCandidate(
        keyword: Keyword,
        request: LyricsMatching.Song,
    ): SearchLyricsResponse.Candidate? {
        for (song in matchingRows(searchSongs(keyword).data.info, request)) {
            currentCoroutineContext().ensureActive()
            bestLyric(searchLyricsByHash(song.hash).candidates, request)?.let { return it }
        }
        return bestLyric(searchLyricsByKeyword(keyword, request.durationSeconds).candidates, request)
    }

    private fun searchSongs(keyword: Keyword): SearchSongResponse {
        val searchQuery =
            buildString {
                append(keyword.title)
                append(" - ")
                append(keyword.artist)
                if (!keyword.album.isNullOrBlank()) {
                    append(" ")
                    append(keyword.album)
                }
            }
        val url =
            buildString {
                append("https://mobileservice.kugou.com/api/v3/search/song")
                append("?version=9108&plat=0&pagesize=$PAGE_SIZE&showtype=0")
                append("&keyword=${URLEncoder.encode(searchQuery, "UTF-8")}")
            }
        return executeGet(url, SearchSongResponse::class.java)
    }

    private fun searchLyricsByKeyword(
        keyword: Keyword,
        duration: Int,
    ): SearchLyricsResponse {
        val searchQuery =
            buildString {
                append(keyword.title)
                append(" - ")
                append(keyword.artist)
                if (!keyword.album.isNullOrBlank()) {
                    append(" ")
                    append(keyword.album)
                }
            }
        val url =
            buildString {
                append("https://lyrics.kugou.com/search")
                append("?ver=1&man=yes&client=pc")
                if (duration > 0) append("&duration=${duration * 1000}")
                append("&keyword=${URLEncoder.encode(searchQuery, "UTF-8")}")
            }
        return executeGet(url, SearchLyricsResponse::class.java)
    }

    private fun searchLyricsByHash(hash: String): SearchLyricsResponse {
        val url = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash"
        return executeGet(url, SearchLyricsResponse::class.java)
    }

    private fun downloadLyrics(
        id: Long,
        accessKey: String,
    ): DownloadLyricsResponse {
        val url = "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=$id&accesskey=$accessKey"
        return executeGet(url, DownloadLyricsResponse::class.java)
    }

    private fun <T> executeGet(
        url: String,
        clazz: Class<T>,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("KuGou returned ${response.code}")
            val body = response.body.string()
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) throw IOException("KuGou sent no JSON: ${trimmed.take(80)}")
            gson.fromJson(body, clazz)
        }
    }

    private fun normalizeTitle(title: String) =
        title
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "")
            .replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "")
            .replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "")
            .replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String) =
        artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")

    fun generateKeyword(
        title: String,
        artist: String,
        album: String? = null,
    ) = Keyword(normalizeTitle(title), normalizeArtist(artist), album)

    private fun String.normalize(): String =
        lines()
            .filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }
                filteredLines.dropLast(tailCutLine).joinToString("\n")
            }

    internal companion object {
        private const val PAGE_SIZE = 8
        private const val HEAD_CUT_LIMIT = 30
        private const val DURATION_TOLERANCE = 8

        // A lyric search by keyword states the length in seconds, one by hash in milliseconds.
        private const val LONGEST_DURATION_IN_SECONDS = 10_000L

        @Suppress("RegExpRedundantEscape")
        private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
        private val BANNED_REGEX = ".+].+[:：].+".toRegex()

        /** The rows naming the requested song, best first. A close length alone proves nothing. */
        fun matchingRows(
            rows: List<SearchSongResponse.Data.Info>,
            request: LyricsMatching.Song,
        ): List<SearchSongResponse.Data.Info> =
            LyricsMatching.ranked(
                rows.filter { closeInLength(it.duration, request) },
                request,
                describe = { LyricsMatching.Song(it.songname, it.singername, it.duration) },
            )

        /** A hash can list another song's lyric first, so each candidate is checked on its own names. */
        fun bestLyric(
            candidates: List<SearchLyricsResponse.Candidate>,
            request: LyricsMatching.Song,
        ): SearchLyricsResponse.Candidate? =
            LyricsMatching.best(
                candidates.filter { closeInLength(it.seconds, request) },
                request,
                describe = { LyricsMatching.Song(it.song, it.singer, it.seconds) },
            )

        private val SearchLyricsResponse.Candidate.seconds: Int
            get() = (if (duration > LONGEST_DURATION_IN_SECONDS) duration / 1000 else duration).toInt()

        private fun closeInLength(
            seconds: Int,
            request: LyricsMatching.Song,
        ): Boolean = request.durationSeconds <= 0 || seconds <= 0 || abs(seconds - request.durationSeconds) <= DURATION_TOLERANCE
    }
}
