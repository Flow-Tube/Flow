package io.github.aedev.flow.player.datasource

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.error.StreamDenialClassifier
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * YouTube-specific HttpDataSource optimized for streaming performance.
 *
 * Key optimizations:
 * - Longer timeouts (30s read) to handle YouTube's variable latency
 * - Proper YouTube headers to avoid bot detection
 * - Range parameter handling for DASH manifests
 * - Cross-protocol redirect support
 */
@UnstableApi
class YouTubeHttpDataSource private constructor(
    private val userAgent: String,
    private val defaultRequestProperties: Map<String, String>,
) : BaseDataSource(true),
    HttpDataSource {
    private var dataSource: DataSource? = null
    private var currentUri: Uri? = null
    private var opened = false

    class Factory : HttpDataSource.Factory {
        private val requestProperties = HashMap<String, String>()
        private var userAgent = DEFAULT_USER_AGENT

        override fun createDataSource(): HttpDataSource = YouTubeHttpDataSource(userAgent, requestProperties.toMap())

        override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
            requestProperties.clear()
            requestProperties.putAll(defaultRequestProperties)
            return this
        }
    }

    companion object {
        private const val TAG = "YouTubeHttpDataSource"
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val clientLock = Any()

        @Volatile
        private var cachedClient: OkHttpClient? = null

        @Volatile
        private var cachedProxySignature: String = ""

        private fun sharedClient(): OkHttpClient {
            val proxySignature = AppProxyManager.currentSignature()
            cachedClient?.takeIf { cachedProxySignature == proxySignature }?.let { return it }

            return synchronized(clientLock) {
                cachedClient?.takeIf { cachedProxySignature == proxySignature } ?: run {
                    val client =
                        AppProxyManager
                            .applyTo(OkHttpClient.Builder())
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .build()
                    cachedProxySignature = proxySignature
                    cachedClient = client
                    client
                }
            }
        }
    }

    @UnstableApi
    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri

        val requestUserAgent =
            if (isYouTubeUri(dataSpec.uri)) {
                resolveYouTubeUserAgent(dataSpec.uri)
            } else {
                userAgent
            }
        val factory =
            OkHttpDataSource
                .Factory(sharedClient())
                .setUserAgent(requestUserAgent)

        val requestHeaders = LinkedHashMap<String, String>()
        requestHeaders.putAll(defaultRequestProperties)
        if (isYouTubeUri(dataSpec.uri)) {
            requestHeaders.putAll(youtubeHeaders(dataSpec))
        }
        if (requestHeaders.isNotEmpty()) {
            factory.setDefaultRequestProperties(requestHeaders)
        }

        dataSource = factory.createDataSource()
        // The inner source is new on every open and never sees the player's listeners, so the
        // bandwidth meter only learns about these bytes from here.
        transferInitializing(dataSpec)
        return try {
            dataSource!!.open(dataSpec).also {
                opened = true
                transferStarted(dataSpec)
            }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403) logForbidden(dataSpec)
            throw e
        }
    }

    private fun logForbidden(dataSpec: DataSpec) {
        val url = dataSpec.uri.toString()
        val expiry = StreamDenialClassifier.describeExpiry(url)
        val kind = StreamDenialClassifier.classify(url)
        val client = StreamDenialClassifier.clientOf(url)
        val itag = StreamDenialClassifier.itagOf(url)
        val pot = StreamDenialClassifier.hasPoToken(url)
        Log.w(
            TAG,
            "HTTP 403 c=$client itag=$itag mime=${StreamDenialClassifier.queryParam(url, "mime")} " +
                "pot=$pot range=${dataSpec.position}+${dataSpec.length} $expiry denial=$kind",
        )
        PlayerDiagnostics.logWarning(
            TAG,
            "403 c=$client itag=$itag pot=$pot range=${dataSpec.position}+${dataSpec.length} $expiry denial=$kind",
        )
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = dataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun close() {
        try {
            dataSource?.close()
        } finally {
            dataSource = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseCode(): Int = (dataSource as? HttpDataSource)?.responseCode ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> = (dataSource as? HttpDataSource)?.responseHeaders ?: emptyMap()

    override fun clearAllRequestProperties() {}

    override fun clearRequestProperty(name: String) {}

    override fun setRequestProperty(
        name: String,
        value: String,
    ) {}

    private fun isYouTubeUri(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.contains("youtube.com") ||
            host.contains("googlevideo.com") ||
            host.contains("ytimg.com")
    }

    private fun resolveYouTubeUserAgent(uri: Uri): String = GoogleVideoRequestPolicy.userAgent(uri.getQueryParameter("c"), userAgent)

    private fun youtubeHeaders(dataSpec: DataSpec): Map<String, String> =
        if (GoogleVideoRequestPolicy.isCaptionRequest(dataSpec.uri.path)) {
            GoogleVideoRequestPolicy.captionHeaders()
        } else {
            GoogleVideoRequestPolicy.headers(
                clientName = dataSpec.uri.getQueryParameter("c"),
                allowGzip = dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP),
            )
        }
}
