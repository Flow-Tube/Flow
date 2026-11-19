package io.github.aedev.flow.player.cache

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * [StreamIdentityCacheKeyFactory]'s keys, plus a note of which video each key was opened for. Keys
 * carry no video id, so this record is the only way to drop one video's data from the shared cache
 * without wiping everything else in it.
 */
@UnstableApi
internal class VideoCacheKeys(
    private val currentVideoId: () -> String?,
) : CacheKeyFactory {
    private val keysByVideo =
        object : LinkedHashMap<String, MutableSet<String>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?): Boolean = size > MAX_VIDEOS
        }

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val key = StreamIdentityCacheKeyFactory.buildCacheKey(dataSpec)
        currentVideoId()?.let { videoId ->
            synchronized(keysByVideo) { keysByVideo.getOrPut(videoId) { mutableSetOf() }.add(key) }
        }
        return key
    }

    /** The keys [videoId]'s streams were cached under, forgotten once returned. */
    fun takeKeys(videoId: String): Set<String> = synchronized(keysByVideo) { keysByVideo.remove(videoId).orEmpty() }

    private companion object {
        const val MAX_VIDEOS = 8
    }
}
