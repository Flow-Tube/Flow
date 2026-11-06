// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.innertube.YouTube
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTubeSubtitle"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrc =
                    YouTube.transcript(id).getOrElse { error ->
                        throw if (meansNoCaptions(error)) LyricsNotFoundException() else error
                    }
                LyricsUtils.parseLyrics(lrc)
            }
        }

    private fun meansNoCaptions(error: Throwable): Boolean =
        error is NullPointerException ||
            (error is ClientRequestException && error.response.status.value in NO_CAPTIONS_STATUSES)

    private companion object {
        val NO_CAPTIONS_STATUSES = setOf(400, 404)
    }
}
