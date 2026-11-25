package io.github.aedev.flow.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.lyrics.LyricsCacheManager
import io.github.aedev.flow.di.MusicCache
import io.github.aedev.flow.di.PlayerCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The caches the settings page measures and clears one by one. */
enum class MediaCacheType { VIDEOS, SONGS, ARTWORK, OTHER }

/** Bytes each cache holds right now. */
@Immutable
data class MediaCacheUsage(
    val videos: Long,
    val songs: Long,
    val artwork: Long,
    val other: Long,
) {
    val total: Long get() = videos + songs + artwork + other

    fun of(type: MediaCacheType): Long =
        when (type) {
            MediaCacheType.VIDEOS -> videos
            MediaCacheType.SONGS -> songs
            MediaCacheType.ARTWORK -> artwork
            MediaCacheType.OTHER -> other
        }
}

/**
 * Measures and empties the app's caches: videos and Shorts, songs, images, and the rest (network
 * responses and lyrics). The caches are only opened when asked, on [Dispatchers.IO].
 *
 * Clearing removes resources the same way Media3's own evictor does while a player is running, so a
 * playing item simply fetches what it needs again; nothing is torn down under it.
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaCacheRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @PlayerCache private val videoCache: Lazy<SimpleCache>,
        @MusicCache private val musicCache: Lazy<SimpleCache>,
        private val imageLoader: Lazy<ImageLoader>,
        private val okHttpClient: OkHttpClient,
    ) {
        suspend fun usage(): MediaCacheUsage =
            withContext(Dispatchers.IO) {
                MediaCacheUsage(
                    videos = safely { videoCache.get().cacheSpace },
                    songs = safely { musicCache.get().cacheSpace },
                    artwork = safely { imageLoader.get().diskCache?.size ?: 0L },
                    other =
                        safely { okHttpClient.cache?.size() ?: 0L } +
                            LyricsCacheManager.cacheSizeBytes(context),
                )
            }

        suspend fun clear(type: MediaCacheType) =
            withContext(Dispatchers.IO) {
                when (type) {
                    MediaCacheType.VIDEOS -> {
                        videoCache.get().removeAll()
                    }

                    MediaCacheType.SONGS -> {
                        musicCache.get().removeAll()
                    }

                    MediaCacheType.ARTWORK -> {
                        val loader = imageLoader.get()
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                    }

                    MediaCacheType.OTHER -> {
                        runCatching { okHttpClient.cache?.evictAll() }.onFailure { Log.w(TAG, "Could not clear the network cache", it) }
                        LyricsCacheManager.clearAllCache(context)
                    }
                }
            }

        private fun SimpleCache.removeAll() {
            keys.toList().forEach { key ->
                runCatching { removeResource(key) }.onFailure { Log.w(TAG, "Could not clear a cached item", it) }
            }
        }

        private inline fun safely(measure: () -> Long): Long =
            runCatching(measure).onFailure { Log.w(TAG, "Could not measure a cache", it) }.getOrDefault(0L)

        private companion object {
            const val TAG = "MediaCacheRepository"
        }
    }
