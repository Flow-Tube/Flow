// ============================================================================
// THIS IMPLEMENTATION WAS INSPIRED BY METROLIST
// ============================================================================

package io.github.aedev.flow.data.lyrics

import okhttp3.OkHttpClient

/**
 * LyricsProvider implementation for the BetterLyrics service.
 * Delegates to BetterLyrics service which returns structured LyricsEntry with word timestamps.
 */
class BetterLyricsProvider(
    client: OkHttpClient,
) : LyricsProvider {
    override val name = "BetterLyrics"

    private val betterLyrics = BetterLyrics(client)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> = betterLyrics.getLyrics(title, LyricsMatching.cleanArtist(artist), duration, album)
}
