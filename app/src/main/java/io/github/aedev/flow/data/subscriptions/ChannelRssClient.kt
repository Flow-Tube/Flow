package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.network.ProxyAwareClient
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place the app fetches a YouTube channel's RSS feed.
 *
 * Both the subscription feed and the background new-upload check go through here, so there is one
 * connection pool, one timeout policy and one parser for the same endpoint.
 */
@Singleton
class ChannelRssClient internal constructor(
    private val httpClient: () -> OkHttpClient,
    private val sleep: suspend (Long) -> Unit,
) {
    @Inject
    constructor() : this(
        ProxyAwareClient {
            connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }::get,
        { delay(it) },
    )

    /**
     * A [Result] rather than a nullable feed: the callers distinguish "this channel has nothing
     * new" from "this channel could not be reached", and only the latter is worth surfacing.
     *
     * A sweep over hundreds of channels meets the odd 429 or 5xx; one spaced retry turns most of
     * them into a feed instead of a channel whose uploads vanish until the next refresh.
     *
     * YouTube also answers 404 for live channels now and then (#1946), so a 404 asks once more
     * through the channel's uploads playlist, which has its own feed of the same shape.
     */
    suspend fun fetch(channelId: String): Result<ChannelRssFeed> =
        withContext(PerformanceDispatcher.networkIO) {
            val channelFeed = String.format(CHANNEL_FEED_URL_FORMAT, channelId)
            val first = attempt(channelFeed, channelId)
            if (first.notFound) {
                val uploads = uploadsPlaylistId(channelId) ?: return@withContext first.result
                return@withContext attempt(String.format(PLAYLIST_FEED_URL_FORMAT, uploads), channelId).result
            }
            val retryAfterMs = first.retryAfterMs ?: return@withContext first.result
            sleep(retryAfterMs)
            attempt(channelFeed, channelId).result
        }

    private class Attempt(
        val result: Result<ChannelRssFeed>,
        val retryAfterMs: Long? = null,
        val notFound: Boolean = false,
    )

    private fun uploadsPlaylistId(channelId: String): String? =
        channelId
            .takeIf { it.startsWith(CHANNEL_ID_PREFIX) }
            ?.let { UPLOADS_PLAYLIST_PREFIX + it.removePrefix(CHANNEL_ID_PREFIX) }

    private fun attempt(
        url: String,
        channelId: String,
    ): Attempt =
        try {
            val request = Request.Builder().url(url).build()
            httpClient().newCall(request).execute().use { response ->
                val code = response.code
                when {
                    code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR -> {
                        Attempt(
                            result = Result.failure(IOException("HTTP $code for channel $channelId")),
                            retryAfterMs = retryDelayMs(response.header("Retry-After")),
                        )
                    }

                    !response.isSuccessful -> {
                        Attempt(
                            result = Result.failure(IOException("HTTP $code for channel $channelId")),
                            notFound = code == HTTP_NOT_FOUND,
                        )
                    }

                    else -> {
                        val body = response.body.string()
                        if (body.isEmpty()) {
                            Attempt(Result.failure(IOException("Empty RSS body for channel $channelId")))
                        } else {
                            Attempt(runCatching { ChannelRssParser.parse(body) })
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Attempt(Result.failure(e), retryAfterMs = DEFAULT_RETRY_DELAY_MS)
        } catch (e: RuntimeException) {
            Attempt(Result.failure(e))
        }

    private fun retryDelayMs(retryAfterHeader: String?): Long =
        retryAfterHeader
            ?.trim()
            ?.toLongOrNull()
            ?.times(1000L)
            ?.coerceIn(DEFAULT_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
            ?: DEFAULT_RETRY_DELAY_MS

    private companion object {
        const val CHANNEL_FEED_URL_FORMAT = "https://www.youtube.com/feeds/videos.xml?channel_id=%s"
        const val PLAYLIST_FEED_URL_FORMAT = "https://www.youtube.com/feeds/videos.xml?playlist_id=%s"
        const val CHANNEL_ID_PREFIX = "UC"
        const val UPLOADS_PLAYLIST_PREFIX = "UU"
        const val TIMEOUT_SECONDS = 30L
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val DEFAULT_RETRY_DELAY_MS = 1_500L
        const val MAX_RETRY_DELAY_MS = 10_000L
    }
}
