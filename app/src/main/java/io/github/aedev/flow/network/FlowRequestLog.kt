package io.github.aedev.flow.network

import android.util.Log
import io.github.aedev.flow.BuildConfig
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/**
 * Debug-only request log under the `FlowRequests` tag: one line per YouTube host call with the
 * endpoint, client, video id or continuation prefix, response bytes and elapsed time. Release
 * builds never install it, so they pay nothing per request.
 */
object FlowRequestLog : Interceptor {
    private const val TAG = "FlowRequests"
    private const val MAX_BODY_SCAN_BYTES = 64L * 1024L
    private const val CONTINUATION_PREFIX_LENGTH = 16

    private val clientNameRegex = Regex("\"clientName\"\\s*:\\s*\"([^\"]+)\"")
    private val videoIdRegex = Regex("\"videoId\"\\s*:\\s*\"([^\"]+)\"")
    private val continuationRegex = Regex("\"continuation\"\\s*:\\s*\"([^\"]+)\"")

    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (BuildConfig.DEBUG && builder.interceptors().none { it === this }) {
            builder.addInterceptor(this)
        }
        return builder
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isYouTubeHost(request.url.host)) return chain.proceed(request)
        val prefix =
            describe(
                method = request.method,
                host = request.url.host,
                path = request.url.encodedPath,
                clientHeader = request.header("X-YouTube-Client-Name"),
                body = request.bodyText(),
                videoQuery = request.url.queryParameter("v"),
            )
        val startNs = System.nanoTime()
        val response =
            try {
                chain.proceed(request)
            } catch (e: IOException) {
                Log.d(TAG, "$prefix failed ${elapsedMs(startNs)}ms ${e.javaClass.simpleName}")
                throw e
            }
        val cached = if (response.networkResponse == null && response.cacheResponse != null) " cache" else ""
        val counted =
            CountingBody(response.body) { bytes ->
                Log.d(TAG, "$prefix ${response.code} ${bytes}B ${elapsedMs(startNs)}ms$cached")
            }
        return response.newBuilder().body(counted).build()
    }

    internal fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtubei.googleapis.com"

    internal fun describe(
        method: String,
        host: String,
        path: String,
        clientHeader: String?,
        body: String?,
        videoQuery: String?,
    ): String {
        val client = body?.let { clientNameRegex.find(it)?.groupValues?.get(1) } ?: clientHeader ?: "-"
        val videoId = body?.let { videoIdRegex.find(it)?.groupValues?.get(1) } ?: videoQuery
        val continuation = body?.let { continuationRegex.find(it)?.groupValues?.get(1) }
        val subject =
            videoId?.let { "v=$it" }
                ?: continuation?.let { "c=${it.take(CONTINUATION_PREFIX_LENGTH)}" }
                ?: "-"
        return "$method $path $client $host $subject"
    }

    private fun Request.bodyText(): String? {
        val requestBody = body ?: return null
        if (requestBody.isOneShot() || requestBody.isDuplex()) return null
        val length = requestBody.contentLength()
        if (length !in 0..MAX_BODY_SCAN_BYTES) return null
        return runCatching { Buffer().also { requestBody.writeTo(it) }.readUtf8() }.getOrNull()
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    internal class CountingBody(
        private val delegate: ResponseBody,
        private val onDone: (Long) -> Unit,
    ) : ResponseBody() {
        private var bytes = 0L
        private var reported = false

        private val counted: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val read = super.read(sink, byteCount)
                    if (read == -1L) report() else bytes += read
                    return read
                }

                override fun close() {
                    report()
                    super.close()
                }
            }.buffer()
        }

        private fun report() {
            if (reported) return
            reported = true
            onDone(bytes)
        }

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = counted
    }
}
