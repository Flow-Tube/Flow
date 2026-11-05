// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics

import io.github.aedev.flow.data.lyrics.kugou.KuGou
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class KuGouLyricsProvider(
    client: OkHttpClient,
) : LyricsProvider {
    override val name = "KuGou"

    private val kuGou = KuGou(client)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrc = kuGou.getLyrics(title, artist, duration, album).getOrThrow()
                val entries = LyricsUtils.parseLyrics(lrc)
                if (isInstrumentalNotice(entries)) emptyList() else entries
            }
        }

    internal companion object {
        // KuGou answers instrumentals with one line, "纯音乐，请欣赏" ("pure music, please enjoy"), not with no lyrics.
        private val INSTRUMENTAL_NOTICE = "纯音乐[，,]?\\s*请欣赏".toRegex()

        fun isInstrumentalNotice(entries: List<LyricsEntry>): Boolean =
            entries.isNotEmpty() && entries.all { it.text.trim().matches(INSTRUMENTAL_NOTICE) }
    }
}
