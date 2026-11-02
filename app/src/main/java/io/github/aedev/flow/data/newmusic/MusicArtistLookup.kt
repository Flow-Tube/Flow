package io.github.aedev.flow.data.newmusic

import io.github.aedev.flow.data.music.model.MusicArtist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The credited artists of a song, each with its id, for a track that only kept the first one: history,
 * liked songs and playlists store a single channel id per song. One lookup per song per process; a
 * second tap while the first is running waits for it instead of asking again.
 */
@Singleton
class MusicArtistLookup internal constructor(
    private val fetch: suspend (videoId: String) -> List<MusicArtist>,
) {
    @Inject
    constructor() : this({ videoId ->
        InnertubeMusicService
            .fetchQueue(videoIds = listOf(videoId))
            .firstOrNull()
            ?.artists
            .orEmpty()
    })

    private val mutex = Mutex()
    private val found =
        object : LinkedHashMap<String, List<MusicArtist>>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MusicArtist>>?): Boolean = size > CACHE_SIZE
        }

    /** The song's artists that carry an id, or an empty list when YouTube gave none in time. */
    suspend fun artistsOf(videoId: String): List<MusicArtist> =
        mutex.withLock {
            found[videoId] ?: withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { fetch(videoId) }
                ?.filter { !it.id.isNullOrBlank() }
                ?.also { if (it.isNotEmpty()) found[videoId] = it }
                .orEmpty()
        }

    private companion object {
        const val CACHE_SIZE = 64
        const val LOOKUP_TIMEOUT_MS = 6_000L
    }
}
