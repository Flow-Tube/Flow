package io.github.aedev.flow.data.repository

import io.github.aedev.flow.di.NetworkIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Titles in the video's own language. Every list response YouTube sends is translated into the
 * request language, and no request setting turns that off; oEmbed is the one cheap response that
 * is not (about 500 bytes against 200 KB for `/player`). Live-probed 2026-10-16 (#1230).
 *
 * One request per video per process: answers and refusals are cached, concurrent asks share one
 * request, and [MAX_PARALLEL] keeps a fast scroll from filling the network pool.
 */
@Singleton
class OriginalTitleRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
        @NetworkIoDispatcher private val dispatcher: CoroutineDispatcher,
    ) {
        private val answers = BoundedCache(CAPACITY)
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
        private val permits = Semaphore(MAX_PARALLEL)
        private val answered =
            MutableSharedFlow<String>(extraBufferCapacity = ANSWER_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** What a request already answered, for a card's first frame. */
        fun cached(videoId: String): String? = answers[videoId]?.ifEmpty { null }

        /** Whether a request already answered for [videoId], even when the answer was no title. */
        fun isAnswered(videoId: String): Boolean = answers[videoId] != null

        /** [cached], updated when a lookup started elsewhere answers; never requests anything itself. */
        fun cachedUpdates(videoId: String): Flow<String?> =
            answered
                .filter { it == videoId }
                .map { cached(videoId) }
                .onStart { emit(cached(videoId)) }

        suspend fun originalTitle(videoId: String): String? {
            answers[videoId]?.let { return it.ifEmpty { null } }
            val mine = CompletableDeferred<String?>()
            inFlight.putIfAbsent(videoId, mine)?.let { return it.await() }
            try {
                val title = permits.withPermit { fetch(videoId) }
                mine.complete(title)
                return title
            } catch (e: CancellationException) {
                mine.complete(null)
                throw e
            } finally {
                inFlight.remove(videoId, mine)
            }
        }

        private suspend fun fetch(videoId: String): String? =
            withContext(dispatcher) {
                val url =
                    OEMBED_URL
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("url", "https://www.youtube.com/watch?v=$videoId")
                        .addQueryParameter("format", "json")
                        .build()
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        when {
                            // Embedding turned off, or the video is gone: both are final answers.
                            response.code == 401 || response.code == 404 -> {
                                store(videoId, "")
                                null
                            }

                            !response.isSuccessful -> {
                                null
                            }

                            else -> {
                                parseTitle(response.body.string()).also { store(videoId, it.orEmpty()) }
                            }
                        }
                    }
                } catch (e: IOException) {
                    null
                }
            }

        private fun store(
            videoId: String,
            title: String,
        ) {
            answers[videoId] = title
            answered.tryEmit(videoId)
        }

        private class BoundedCache(
            private val capacity: Int,
        ) {
            private val map =
                object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
                }

            @Synchronized
            operator fun get(key: String): String? = map[key]

            @Synchronized
            operator fun set(
                key: String,
                value: String,
            ) {
                map[key] = value
            }
        }

        internal companion object {
            const val OEMBED_URL = "https://www.youtube.com/oembed"
            const val CAPACITY = 1_000
            const val MAX_PARALLEL = 4
            private const val ANSWER_BUFFER = 64

            private val json = Json { ignoreUnknownKeys = true }

            @Serializable
            private data class OEmbed(
                val title: String? = null,
            )

            fun parseTitle(body: String): String? =
                runCatching { json.decodeFromString<OEmbed>(body).title }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
        }
    }
