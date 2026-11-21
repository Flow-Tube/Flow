package io.github.aedev.flow.data.repository

import io.github.aedev.flow.data.model.DeArrowContent
import io.github.aedev.flow.di.NetworkIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeArrow branding (community titles and thumbnails, https://dearrow.ajay.app/) per video.
 *
 * A video nobody has submitted for answers 404 with a usable body (the random frame time), so 404
 * is cached like any answer. One request per video per [TTL_MS]: concurrent asks share a request
 * that finishes even when its askers leave, and [MAX_PARALLEL] keeps a fast scroll from filling the
 * network pool.
 */
@Singleton
class DeArrowRepository internal constructor(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val clockMs: () -> Long,
) {
    @Inject
    constructor(
        client: OkHttpClient,
        @NetworkIoDispatcher dispatcher: CoroutineDispatcher,
    ) : this(client, dispatcher, { System.nanoTime() / 1_000_000 })

    private class Entry(
        val content: DeArrowContent?,
        val atMs: Long,
    )

    private val answers =
        object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CAPACITY
        }
    private val lookups = SharedLookups<DeArrowContent?>(CoroutineScope(SupervisorJob() + dispatcher))
    private val permits = Semaphore(MAX_PARALLEL)

    /** What a request already answered, for a card's first frame. */
    fun cached(videoId: String): DeArrowContent? = fresh(videoId)?.content

    /** Whether a request already answered for [videoId], even when the answer was nothing. */
    fun isCached(videoId: String): Boolean = fresh(videoId) != null

    suspend fun branding(videoId: String): DeArrowContent? {
        fresh(videoId)?.let { return it.content }
        return lookups.await(videoId) { permits.withPermit { fetch(videoId) } }
    }

    private suspend fun fetch(videoId: String): DeArrowContent? =
        withContext(dispatcher) {
            val url =
                BRANDING_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("videoID", videoId)
                    .build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 404) return@use null
                    parse(response.body.string()).also { store(videoId, it) }
                }
            } catch (e: IOException) {
                null
            }
        }

    @Synchronized
    private fun fresh(videoId: String): Entry? {
        val entry = answers[videoId] ?: return null
        if (clockMs() - entry.atMs < TTL_MS) return entry
        answers.remove(videoId)
        return null
    }

    @Synchronized
    private fun store(
        videoId: String,
        content: DeArrowContent?,
    ) {
        answers[videoId] = Entry(content, clockMs())
    }

    internal companion object {
        const val BRANDING_URL = "https://sponsor.ajay.app/api/branding"
        const val CAPACITY = 1_000
        const val MAX_PARALLEL = 4
        const val TTL_MS = 60 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): DeArrowContent? = runCatching { json.decodeFromString<DeArrowContent>(body) }.getOrNull()
    }
}
