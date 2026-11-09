package io.github.aedev.flow.data.lyrics

import android.content.Context

/**
 * The source answered and has nothing for this song. Every other failure means it could not answer,
 * so only this one lets the app remember that a song has no lyrics.
 */
class LyricsNotFoundException(
    message: String = "No lyrics found",
) : Exception(message)

/**
 * The source is overloaded or too slow to answer, so it rests before it is asked again: longer than
 * the usual rest when the server asked for [retryAfterMs].
 */
class LyricsSourceBusyException(
    message: String,
    val retryAfterMs: Long? = null,
) : Exception(message)

interface LyricsProvider {
    val name: String

    /** A slow reply means the service is overloaded, so missing the deadline rests it like [LyricsSourceBusyException]. */
    val restsAfterTimeout: Boolean get() = false

    fun isEnabled(context: Context): Boolean = true

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<List<LyricsEntry>>
}
