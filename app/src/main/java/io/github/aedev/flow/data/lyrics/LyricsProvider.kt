package io.github.aedev.flow.data.lyrics

import android.content.Context

/**
 * The source answered and has nothing for this song. Every other failure means it could not answer,
 * so only this one lets the app remember that a song has no lyrics.
 */
class LyricsNotFoundException(
    message: String = "No lyrics found",
) : Exception(message)

interface LyricsProvider {
    val name: String

    fun isEnabled(context: Context): Boolean = true

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<List<LyricsEntry>>
}
