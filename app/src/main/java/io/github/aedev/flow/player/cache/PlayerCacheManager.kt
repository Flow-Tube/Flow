package io.github.aedev.flow.player.cache

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.MediaCacheLimits
import io.github.aedev.flow.data.local.MediaCacheSizes
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.datasource.FakeStreamWall
import io.github.aedev.flow.player.datasource.FakeStreamWallDataSource
import io.github.aedev.flow.player.datasource.RefusedStreamRetryDataSource
import io.github.aedev.flow.player.datasource.RefusedStreamSwapper
import io.github.aedev.flow.player.datasource.StreamSwapTable
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.error.StreamDenialKind
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.TizenStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerCacheManager(
    private val context: Context,
    currentVideoId: () -> String?,
) {
    companion object {
        private const val TAG = "PlayerCacheManager"

        /**
         * Cache sizes resolved by the last [preload], or null if preload has not run.
         *
         * This is what keeps [initialize] off DataStore on the cold-start path: the preferences
         * have already been read on a background thread by the time the player is built.
         */
        @Volatile
        private var preloadedLimits: MediaCacheLimits? = null

        /**
         * Warms the cache-size preferences and the video [SimpleCache] on [Dispatchers.IO].
         *
         * Both are disk-bound — DataStore reads and parses its backing file, and SimpleCache
         * opens a SQLite index and scans the cache directory — so neither belongs on the
         * main thread during startup. Call this before [initialize] on any latency-sensitive
         * path; [initialize] stays correct without it, just blocking.
         */
        suspend fun preload(context: Context): Unit =
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val limits = runCatching { readLimits(appContext) }.getOrDefault(MediaCacheLimits.DEFAULT)
                preloadedLimits = limits
                runCatching { SharedPlayerCacheProvider.getOrCreate(appContext, maxCacheSizeBytes = limits.videoBytes) }
                    .onFailure { Log.w(TAG, "Cache preload failed; initialize() will retry", it) }
            }

        /**
         * The configured cache sizes, from [preload] when it has run. Otherwise a blocking read: the
         * media service, Hilt and Coil can build a cache without going through the cold-start path.
         */
        fun configuredLimits(context: Context): MediaCacheLimits =
            preloadedLimits ?: runBlocking { readLimits(context.applicationContext) }.also { preloadedLimits = it }

        private suspend fun readLimits(context: Context): MediaCacheLimits {
            val preferences = PlayerPreferences(context)
            return MediaCacheLimits(
                videoBytes = MediaCacheSizes.mediaBytes(preferences.mediaCacheSizeMb.first()),
                musicBytes = MediaCacheSizes.mediaBytes(preferences.musicCacheSizeMb.first()),
                artworkBytes = MediaCacheSizes.artworkBytes(preferences.artworkCacheSizeMb.first()),
            )
        }
    }

    private val videoCacheKeys = VideoCacheKeys(currentVideoId)

    private val refusedStreams =
        RefusedStreamSwapper(
            table = StreamSwapTable(),
            reportDenied = ClientGateTracker::reportDenied,
            resolve = ::resolveReplacement,
        )

    private var cache: SimpleCache? = null

    // Data source factories
    private var sharedDataSourceFactory: DataSource.Factory? = null
    private var sharedDashDataSourceFactory: DataSource.Factory? = null
    private var sharedProgressiveDataSourceFactory: DataSource.Factory? = null
    private var sharedHlsDataSourceFactory: DataSource.Factory? = null
    private var sharedLiveDashDataSourceFactory: DataSource.Factory? = null
    private var sharedLiveHlsDataSourceFactory: DataSource.Factory? = null

    /**
     * Initialize cache and data source factories.
     */
    fun initialize(): Boolean {
        // Build shared DataSource Factories (NewPipe Architecture)
        val dashHttpFactory = YouTubeHttpDataSource.Factory()
        val progressiveHttpFactory = YouTubeHttpDataSource.Factory()
        val hlsHttpFactory = YouTubeHttpDataSource.Factory()

        val fakeWall = if (BuildConfig.DEBUG) FakeStreamWall.forDebugBuild(context) else null
        val liveDashUpstream = DefaultDataSource.Factory(context, dashHttpFactory)
        val dashUpstream = DefaultDataSource.Factory(context, fakeWall.around(dashHttpFactory)).swappingRefusals()
        val progressiveUpstream = DefaultDataSource.Factory(context, fakeWall.around(progressiveHttpFactory)).swappingRefusals()
        val hlsUpstream = DefaultDataSource.Factory(context, hlsHttpFactory)
        sharedLiveDashDataSourceFactory = liveDashUpstream
        sharedLiveHlsDataSourceFactory = hlsUpstream

        // Legacy/Fallback
        val legacyHttpFactory = YouTubeHttpDataSource.Factory()
        val upstream = DefaultDataSource.Factory(context, legacyHttpFactory)

        try {
            cache = SharedPlayerCacheProvider.getOrCreate(context, maxCacheSizeBytes = configuredLimits(context).videoBytes)

            val cacheFactory =
                CacheDataSource
                    .Factory()
                    .setCache(cache!!)
                    .setCacheKeyFactory(videoCacheKeys)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // Create the 3 specific factories
            sharedDashDataSourceFactory =
                CacheDataSource
                    .Factory()
                    .setCache(cache!!)
                    .setCacheKeyFactory(videoCacheKeys)
                    .setUpstreamDataSourceFactory(dashUpstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            sharedProgressiveDataSourceFactory =
                CacheDataSource
                    .Factory()
                    .setCache(cache!!)
                    .setCacheKeyFactory(videoCacheKeys)
                    .setUpstreamDataSourceFactory(progressiveUpstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            sharedHlsDataSourceFactory =
                CacheDataSource
                    .Factory()
                    .setCache(cache!!)
                    .setCacheKeyFactory(videoCacheKeys)
                    .setUpstreamDataSourceFactory(hlsUpstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            sharedDataSourceFactory = cacheFactory.setUpstreamDataSourceFactory(upstream)

            Log.d(TAG, "Cache initialized successfully")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Cache not available, using upstream only", e)
            sharedDataSourceFactory = upstream
            sharedDashDataSourceFactory = dashUpstream
            sharedProgressiveDataSourceFactory = progressiveUpstream
            sharedHlsDataSourceFactory = hlsUpstream
            sharedLiveDashDataSourceFactory = liveDashUpstream
            sharedLiveHlsDataSourceFactory = hlsUpstream
            return false
        }
    }

    /**
     * Get the legacy/default data source factory.
     */
    fun getDataSourceFactory(): DataSource.Factory? = sharedDataSourceFactory

    /**
     * A source that fills the video cache under the same keys and upstream the DASH player reads
     * with, waiting on spans the player is loading. Null when the cache could not be opened.
     */
    fun createWritingDataSource(): CacheDataSource? =
        (sharedDashDataSourceFactory as? CacheDataSource.Factory)?.createDataSourceForDownloading()

    /**
     * Get the DASH-specific data source factory.
     */
    fun getDashDataSourceFactory(): DataSource.Factory? = sharedDashDataSourceFactory

    /**
     * Live DASH manifests are timeline data, so keep them off the persistent media cache.
     */
    fun getLiveDashDataSourceFactory(): DataSource.Factory? = sharedLiveDashDataSourceFactory

    /**
     * Get the progressive media data source factory.
     */
    fun getProgressiveDataSourceFactory(): DataSource.Factory? = sharedProgressiveDataSourceFactory

    /**
     * Get the HLS data source factory.
     */
    fun getHlsDataSourceFactory(): DataSource.Factory? = sharedHlsDataSourceFactory

    /**
     * Live HLS playlists move constantly, so use an uncached upstream source for them.
     */
    fun getLiveHlsDataSourceFactory(): DataSource.Factory? = sharedLiveHlsDataSourceFactory

    /**
     * Get cache size in bytes.
     */
    fun getCacheSize(): Long = cache?.cacheSpace ?: 0L

    /** Drops what [videoId]'s streams left in the cache, leaving every other video and song alone. */
    fun clearVideo(videoId: String) {
        val cache = cache ?: return
        val keys = videoCacheKeys.takeKeys(videoId)
        keys.forEach { key ->
            runCatching { cache.removeResource(key) }.onFailure { Log.w(TAG, "Could not clear a cached stream of $videoId", it) }
        }
        Log.d(TAG, "Cleared ${keys.size} cached streams of $videoId")
    }

    /**
     * Release cache resources.
     */
    fun release() {
        try {
            cache = null
            sharedDataSourceFactory = null
            sharedDashDataSourceFactory = null
            sharedProgressiveDataSourceFactory = null
            sharedHlsDataSourceFactory = null
            sharedLiveDashDataSourceFactory = null
            sharedLiveHlsDataSourceFactory = null
            Log.d(TAG, "Cache references cleared (SimpleCache lifecycle managed by SharedPlayerCacheProvider)")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing cache", e)
        }
    }

    /**
     * Check if cache is initialized and available.
     */
    fun isCacheAvailable(): Boolean = cache != null

    /**
     * Records which video [urls] belong to, so a refusal of one of them mid-playback can be served
     * from a replacement of the same file instead of failing the player.
     */
    fun registerStreams(
        videoId: String,
        urls: Collection<String?>,
    ) = refusedStreams.register(videoId, urls)

    // A walled visitor's app clients and token-backed web clients all stop at the same minute, so a
    // wall goes to TV_TIZEN; an expired URL only needs the same ladder to mint it again.
    private suspend fun resolveReplacement(
        videoId: String,
        kind: StreamDenialKind,
    ): List<PlayerResponse.StreamingData.Format>? {
        val result =
            when (kind) {
                StreamDenialKind.URL_EXPIRED -> InnerTubeVideoStreamExtractor.extract(videoId)
                else -> TizenStreamResolver.resolve(videoId)
            } ?: return null
        return result.videoFormats + result.audioFormats
    }

    private fun DataSource.Factory.swappingRefusals(): DataSource.Factory =
        RefusedStreamRetryDataSource.Factory(
            ResolvingDataSource.Factory(this, refusedStreams::resolveDataSpec),
            refusedStreams::onRefused,
        )

    private fun FakeStreamWall?.around(http: DataSource.Factory): DataSource.Factory =
        this?.let { FakeStreamWallDataSource.Factory(http, it) } ?: http
}
