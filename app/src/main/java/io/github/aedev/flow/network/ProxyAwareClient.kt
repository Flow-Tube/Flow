package io.github.aedev.flow.network

import okhttp3.OkHttpClient

/**
 * One [OkHttpClient] per proxy configuration, rebuilt only when that configuration changes, on the
 * [SharedConnectionPool]: a proxy switch still takes effect without a restart.
 */
class ProxyAwareClient(
    private val configure: OkHttpClient.Builder.() -> OkHttpClient.Builder = { this },
) {
    private val lock = Any()

    @Volatile
    private var cachedClient: OkHttpClient? = null

    @Volatile
    private var cachedProxySignature: String? = null

    fun get(): OkHttpClient {
        val signature = AppProxyManager.currentSignature()
        cachedClient?.let { if (cachedProxySignature == signature) return it }
        return synchronized(lock) {
            cachedClient?.let { if (cachedProxySignature == signature) return it }
            AppProxyManager
                .applyTo(OkHttpClient.Builder().connectionPool(SharedConnectionPool.pool))
                .configure()
                .build()
                .also {
                    cachedClient = it
                    cachedProxySignature = signature
                }
        }
    }
}
