package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube Music's own lyrics, asked for by video id, so they can't belong to another song. Song
 * rows have timed lines. Music video rows have none, and fall back to the Lyrics tab's plain text.
 */
class YouTubeLyricsProvider : LyricsProvider {
    override val name = NAME

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = YouTube.lyricsEndpoint(id).getOrThrow() ?: throw LyricsNotFoundException()
                val timed = YouTube.timedLyrics(endpoint).getOrNull().orEmpty()
                timed.map { LyricsEntry(time = it.startMs, text = it.text) }.ifEmpty {
                    val plain = YouTube.lyrics(endpoint).getOrThrow() ?: throw LyricsNotFoundException()
                    listOf(LyricsEntry(time = 0L, text = plain))
                }
            }
        }

    internal companion object {
        const val NAME = "YouTube"
    }
}
