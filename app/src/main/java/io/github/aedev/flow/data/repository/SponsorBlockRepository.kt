package io.github.aedev.flow.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.network.AppProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A segment's times in seconds. A [videoDurationSeconds] of zero means it wasn't recorded. */
data class TimedSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val videoDurationSeconds: Double,
)

@Serializable
private class HashedVideo(
    @SerialName("videoID") val videoId: String,
    val segments: List<HashedSegment> = emptyList(),
)

@Serializable
private class HashedSegment(
    val category: String = "",
    val actionType: String = "",
    val segment: List<Double> = emptyList(),
    val videoDuration: Double = 0.0,
)

@Singleton
class SponsorBlockRepository
    @Inject
    constructor() {
        private val client: OkHttpClient
            get() = AppProxyManager.applyTo(OkHttpClient.Builder()).build()
        private val gson = Gson()
        private val segmentListType = object : TypeToken<List<SponsorBlockSegment>>() {}.type

        suspend fun getSegments(videoId: String): List<SponsorBlockSegment> =
            withContext(Dispatchers.IO) {
                try {
                    val url = segmentsUrl(videoId)

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .build()

                    val response = client.newCall(request).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val responseBody = resp.body?.string() ?: return@withContext emptyList()
                            return@withContext parseSegments(responseBody) ?: emptyList()
                        } else {
                            return@withContext emptyList()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext emptyList()
                }
            }

        /**
         * The parts of a video marked as non-music, or null if the lookup failed. Only a short prefix
         * of the id's hash is sent, because this runs for each song played rather than for videos
         * someone chose to use SponsorBlock on. No segments comes back as a 404.
         */
        suspend fun getNonMusicSegments(videoId: String): List<TimedSegment>? =
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(nonMusicSegmentsUrl(videoId)).build()
                try {
                    client
                        .newBuilder()
                        .callTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { response ->
                            when {
                                response.code == HTTP_NOT_FOUND -> emptyList()
                                response.isSuccessful -> parseHashedSegments(response.body.string(), videoId)
                                else -> null
                            }
                        }
                } catch (e: IOException) {
                    Log.w(TAG, "Non-music lookup failed: ${e.message}")
                    null
                } catch (e: SerializationException) {
                    Log.w(TAG, "Non-music lookup unreadable: ${e.message}")
                    null
                }
            }

        /**
         * Read segments back from a stored payload. Null means "nothing stored or nothing readable",
         * which callers treat differently from an empty segment list.
         */
        fun parseSegments(json: String?): List<SponsorBlockSegment>? {
            if (json.isNullOrBlank()) return null
            return try {
                gson.fromJson(json, segmentListType)
            } catch (e: Exception) {
                null
            }
        }

        /** Serialize segments for the download store, in the shape [parseSegments] reads back. */
        fun serializeSegments(segments: List<SponsorBlockSegment>): String = gson.toJson(segments)

        /**
         * Submit a new SponsorBlock segment.
         * Uses query parameters as required by the SponsorBlock API.
         * @return true if the submission was accepted (HTTP 200), false otherwise.
         */
        suspend fun submitSegment(
            videoId: String,
            startTime: Float,
            endTime: Float,
            category: String,
            userId: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val uuid =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                    val duration = (endTime - startTime)
                    val submitUrl =
                        SKIP_SEGMENTS_URL
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("videoID", videoId)
                            .addQueryParameter("startTime", startTime.toString())
                            .addQueryParameter("endTime", endTime.toString())
                            .addQueryParameter("category", category)
                            .addQueryParameter("userID", userId)
                            .addQueryParameter("userAgent", "FlowYouTube/1.0")
                            .addQueryParameter("UUID", uuid)
                            .addQueryParameter("duration", duration.toString())
                            .build()

                    val request =
                        Request
                            .Builder()
                            .url(submitUrl)
                            .post("".toRequestBody())
                            .build()

                    val response = client.newCall(request).execute()
                    response.use { resp -> resp.isSuccessful }
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

        companion object {
            private const val TAG = "SponsorBlockRepository"
            private const val SKIP_SEGMENTS_URL = "https://sponsor.ajay.app/api/skipSegments"
            private const val LOOKUP_TIMEOUT_SECONDS = 8L
            private const val HTTP_NOT_FOUND = 404
            private const val HASH_PREFIX_LENGTH = 4
            private const val SKIP_ACTION = "skip"
            private val hashedJson = Json { ignoreUnknownKeys = true }

            /** Asks for every video sharing the id's hash prefix, so the server can't tell which one is playing. */
            internal fun nonMusicSegmentsUrl(videoId: String): HttpUrl {
                val hash =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(videoId.toByteArray())
                        .toHexString()
                return SKIP_SEGMENTS_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegment(hash.take(HASH_PREFIX_LENGTH))
                    .addQueryParameter("categories", listOf(SponsorBlockCategories.MUSIC_OFF_TOPIC).toJsonArray())
                    .addQueryParameter("actionTypes", listOf(SKIP_ACTION).toJsonArray())
                    .build()
            }

            /** The segments of [videoId] among the videos sharing its hash prefix. */
            internal fun parseHashedSegments(
                json: String,
                videoId: String,
            ): List<TimedSegment> =
                hashedJson
                    .decodeFromString<List<HashedVideo>>(json)
                    .firstOrNull { it.videoId == videoId }
                    ?.segments
                    .orEmpty()
                    .filter {
                        it.category == SponsorBlockCategories.MUSIC_OFF_TOPIC && it.actionType == SKIP_ACTION && it.segment.size == 2
                    }.map { TimedSegment(it.segment[0], it.segment[1], it.videoDuration) }

            /** The lookup for [videoId], asking for every category and action type Flow handles. */
            internal fun segmentsUrl(videoId: String): HttpUrl =
                SKIP_SEGMENTS_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("videoID", videoId)
                    .addQueryParameter("categories", SponsorBlockCategories.all.toJsonArray())
                    .addQueryParameter("actionTypes", SponsorBlockCategories.actionTypes.toJsonArray())
                    .build()

            private fun List<String>.toJsonArray(): String = JsonArray(map { JsonPrimitive(it) }).toString()
        }
    }
