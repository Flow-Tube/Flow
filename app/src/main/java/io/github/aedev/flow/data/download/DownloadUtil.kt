package io.github.aedev.flow.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.di.DownloadCache
import io.github.aedev.flow.di.MusicCache
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.player.datasource.GoogleVideoRequestPolicy
import io.github.aedev.flow.player.datasource.RefusedStreamRetryDataSource
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.error.StreamDenialKind
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.utils.MusicPlayerUtils
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @DownloadCache private val downloadCache: SimpleCache,
        @MusicCache private val musicCache: SimpleCache,
        private val downloadDao: DownloadDao,
    ) {
        companion object {
            private const val TAG = "DownloadUtil"
            private const val CHUNK_LENGTH = 512 * 1024L // per stream request
            private val URL_RANGE_PARAM_REGEX = Regex("""([?&])range=\d+-\d*(&?)""")
        }

        private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Long>>()

        private val okHttpClient: OkHttpClient by lazy {
            AppProxyManager.buildLive(
                OkHttpClient
                    .Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS),
            )
        }

        /**
         * DataSource factory for PLAYBACK - reads from both caches.
         * Chain: downloadCache (read-only) -> musicCache (read-write) -> network
         */
        fun getPlayerDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
            val downloadCacheFactory =
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // A cache read that outlives its span (evicted meanwhile) still holds the music:// uri.
            val networkFactory =
                ResolvingDataSource.Factory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))) { dataSpec ->
                    if (dataSpec.uri.scheme == LocalCopyDataSource.MUSIC_SCHEME) resolveStream(dataSpec) else dataSpec
                }

            val musicCacheFactory =
                CacheDataSource
                    .Factory()
                    .setCache(musicCache)
                    .setUpstreamDataSourceFactory(networkFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val cachedDataSourceFactory =
                downloadCacheFactory
                    .setUpstreamDataSourceFactory(musicCacheFactory)

            val resolvingFactory =
                ResolvingDataSource.Factory(cachedDataSourceFactory) { dataSpec ->
                    if (dataSpec.uri.scheme in setOf("file", "content", "android.resource")) {
                        return@Factory dataSpec
                    }

                    val mediaId = dataSpec.key ?: error("No media id (key) in dataSpec")

                    // A cache hit is bounded to the bytes actually held: an open-ended read would make
                    // the cache fetch the rest itself, and record the end of that fetch as the song's
                    // length. ChunkStitchingDataSource opens the next piece where this one stops.
                    try {
                        val span = downloadCache.cachedSpan(mediaId, dataSpec)
                        if (span > 0) {
                            Log.d(TAG, "[Player] Serving from downloadCache: $mediaId")
                            return@Factory dataSpec.subrange(0, span)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Player] downloadCache check error for $mediaId", e)
                        try {
                            downloadCache.removeResource(mediaId)
                        } catch (_: Exception) {
                        }
                    }

                    try {
                        val span = musicCache.cachedSpan(mediaId, dataSpec)
                        if (span > 0) {
                            Log.d(TAG, "[Player] Serving from musicCache: $mediaId")
                            return@Factory dataSpec.subrange(0, span)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Player] musicCache check error for $mediaId", e)
                    }

                    resolveStream(dataSpec)
                }
            val localFirst = LocalCopyDataSource.Factory(DefaultDataSource.Factory(context), resolvingFactory, ::downloadedSongUri)
            val refusalRetrying =
                RefusedStreamRetryDataSource.Factory(localFirst) { dataSpec, url ->
                    val mediaId = dataSpec.key
                    if (mediaId != null) onStreamRefused(mediaId, url)
                    mediaId != null
                }
            return ChunkStitchingDataSource.Factory(refusalRetrying)
        }

        private fun SimpleCache.cachedSpan(
            key: String,
            dataSpec: DataSpec,
        ): Long = getCachedLength(key, dataSpec.position, dataSpec.length).coerceAtLeast(0L)

        private fun resolveStream(dataSpec: DataSpec): DataSpec {
            val mediaId = dataSpec.key ?: error("No media id (key) in dataSpec")
            songUrlCache[mediaId]?.takeIf(::isReusable)?.let { (url, ua, _) ->
                Log.d(TAG, "[Player] Using cached URL for $mediaId")
                return buildPlaybackDataSpec(dataSpec, url, ua)
            }

            val playbackData =
                runBlocking(Dispatchers.IO) {
                    MusicPlayerUtils.playerResponseForPlayback(mediaId)
                }.getOrThrow()

            val streamUrl = playbackData.streamUrl
            val userAgent = playbackData.usedClient.userAgent
            val expiration = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds - 60) * 1000L

            songUrlCache[mediaId] = Triple(streamUrl, userAgent, expiration)
            Log.d(TAG, "[Player] Resolved $mediaId via ${playbackData.usedClient.clientName}")

            return buildPlaybackDataSpec(dataSpec, streamUrl, userAgent)
        }

        private fun onStreamRefused(
            mediaId: String,
            url: String,
        ) {
            val kind = ClientGateTracker.reportDenied(url)
            if (kind == StreamDenialKind.TOKEN_REJECTED) WebPoTokenSession.reportTokenRejected()
            songUrlCache.remove(mediaId)
            MusicPlayerUtils.forceRefreshForVideo(mediaId)
            Log.w(TAG, "[Player] $mediaId refused (${StreamDenialClassifier.clientOf(url)}, $kind), resolving a fresh url")
        }

        /** The saved file of a finished song download, while it is still there. */
        private fun downloadedSongUri(videoId: String): Uri? {
            val path =
                runCatching { downloadDao.completedAudioPathBlocking(videoId) }
                    .onFailure { Log.w(TAG, "[Player] download lookup failed for $videoId", it) }
                    .getOrNull()
                    ?.takeIf { DownloadFiles.exists(context, it) } ?: return null
            Log.d(TAG, "[Player] Playing the download of $videoId")
            return if (DownloadFiles.isDocument(path)) path.toUri() else Uri.fromFile(File(path))
        }

        // A url resolved before its client was demoted would stall the same way ~30 s in.
        private fun isReusable(entry: Triple<String, String, Long>): Boolean =
            entry.third > System.currentTimeMillis() && !ClientGateTracker.isGated(StreamDenialClassifier.clientOf(entry.first))

        private fun buildPlaybackDataSpec(
            dataSpec: DataSpec,
            streamUrl: String,
            userAgent: String,
        ): DataSpec {
            val requestLength =
                when {
                    dataSpec.length > 0 -> dataSpec.length
                    dataSpec.length == C.LENGTH_UNSET.toLong() -> CHUNK_LENGTH
                    else -> CHUNK_LENGTH
                }

            val client = StreamDenialClassifier.clientOf(streamUrl)
            return dataSpec
                .buildUpon()
                .setUri(removeRangeParameter(streamUrl).toUri())
                .setHttpRequestHeaders(
                    GoogleVideoRequestPolicy.headers(client) + ("User-Agent" to GoogleVideoRequestPolicy.userAgent(client, userAgent)),
                ).setLength(requestLength)
                .build()
        }

        private fun removeRangeParameter(url: String): String {
            val withoutRange =
                URL_RANGE_PARAM_REGEX.replace(url) { match ->
                    val prefix = match.groupValues[1]
                    val hasTrailingParam = match.groupValues[2].isNotEmpty()
                    when {
                        prefix == "?" && hasTrailingParam -> "?"
                        prefix == "?" -> ""
                        hasTrailingParam -> "&"
                        else -> ""
                    }
                }
            return withoutRange.trimEnd('?', '&')
        }

        /**
         * Invalidate URL cache for a specific media ID.
         * Called during error recovery.
         */
        fun invalidateUrlCache(mediaId: String) {
            songUrlCache.remove(mediaId)
            Log.d(TAG, "Invalidated URL cache for $mediaId")
        }

        /**
         * Clear all URL cache entries.
         */
        fun clearUrlCache() {
            songUrlCache.clear()
            Log.d(TAG, "Cleared all URL cache entries")
        }

        /**
         * Aggressive cache clear for error recovery.
         * Clears URL cache, music cache, and triggers force refresh.
         */
        fun performAggressiveCacheClear(mediaId: String) {
            Log.d(TAG, "Performing aggressive cache clear for $mediaId")

            songUrlCache.remove(mediaId)

            try {
                musicCache.removeResource(mediaId)
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing musicCache for $mediaId: ${e.message}")
            }

            MusicPlayerUtils.forceRefreshForVideo(mediaId)
        }
    }
