package io.github.aedev.flow.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.network.ImageRequestLog
import io.github.aedev.flow.network.SharedConnectionPool
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Enhanced Network Module for Blazing Fast Performance
 *
 * Optimizations:
 * - Connection pooling: Reuse connections for faster subsequent requests
 * - HTTP/2 support: Multiplexing for parallel requests on single connection
 * - Aggressive dispatcher: High concurrency for parallel fetching
 * - Optimized timeouts: Fast fail for unresponsive servers
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // Cache configuration
    private const val CACHE_SIZE_MB = 100L * 1024L * 1024L // 100MB cache

    // Timeout configuration (in seconds)
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 30L
    private const val CALL_TIMEOUT = 60L

    // Dispatcher configuration
    private const val MAX_REQUESTS_TOTAL = 32 // Max concurrent requests
    private const val MAX_REQUESTS_PER_HOST = 5 // Max per host

    private const val IMAGE_MAX_REQUESTS = 32
    private const val IMAGE_MAX_REQUESTS_PER_HOST = 16

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient {
        val cacheDirectory = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDirectory, CACHE_SIZE_MB)

        // Dispatcher for high concurrency parallel requests
        val dispatcher =
            Dispatcher().apply {
                maxRequests = MAX_REQUESTS_TOTAL
                maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            }

        return AppProxyManager.buildLive(
            OkHttpClient
                .Builder()
                // Enable HTTP/2 for multiplexing (parallel streams on single connection)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(SharedConnectionPool.pool)
                // High concurrency dispatcher
                .dispatcher(dispatcher)
                // Response caching
                .cache(cache)
                // Optimized timeouts
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
                // Retry on connection failure
                .retryOnConnectionFailure(true)
                // Follow redirects
                .followRedirects(true)
                .followSslRedirects(true),
        )
    }

    /**
     * The client Coil fetches images with. Coil keeps its own disk cache, so this one has no HTTP
     * cache, which would store every image a second time. It has its own dispatcher, so a screen of
     * thumbnails is not queued five at a time behind API calls; i.ytimg speaks HTTP/2, which
     * carries the extra requests on one connection. The connection pool, proxy and request log stay
     * shared with the main client.
     */
    @Provides
    @Singleton
    @ImageClient
    fun provideImageClient(okHttpClient: OkHttpClient): OkHttpClient =
        okHttpClient
            .newBuilder()
            .cache(null)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = IMAGE_MAX_REQUESTS
                    maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
                },
            ).apply { if (BuildConfig.DEBUG) addInterceptor(ImageRequestLog) }
            .build()

    /**
     * Provides a lightweight client for quick metadata requests
     * Uses shorter timeouts for fast-fail behavior
     */
    @Provides
    @Singleton
    @MetadataClient
    fun provideMetadataClient(
        @ApplicationContext context: Context,
    ): OkHttpClient =
        AppProxyManager.buildLive(
            OkHttpClient
                .Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(SharedConnectionPool.pool)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true),
        )
}

/** The OkHttp client Coil loads images with. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageClient

/**
 * Qualifier annotation for metadata-specific HTTP client
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MetadataClient
