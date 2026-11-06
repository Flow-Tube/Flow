// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.data.lyrics.paxsenix.Paxsenix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class PaxsenixLyricsProvider(
    client: OkHttpClient,
) : LyricsProvider {
    override val name = "Paxsenix"

    private val paxsenix = Paxsenix(client)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrc = paxsenix.getLyrics(title, LyricsMatching.cleanArtist(artist), duration, album).getOrThrow()
                LyricsUtils.parseLyrics(lrc)
            }
        }
}
