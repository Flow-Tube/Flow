package io.github.aedev.flow.data.contentfilter.aislist

import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What a report would be about, worked out before the viewer confirms it. */
sealed interface AiReportCheck {
    data class Ready(
        val handle: String,
    ) : AiReportCheck

    data class AlreadyListed(
        val verdict: AiVerdict,
    ) : AiReportCheck

    data object NoName : AiReportCheck

    data object NoHandle : AiReportCheck
}

enum class AiReportResult {
    SENT,
    BUSY,
    RATE_LIMITED,
    REJECTED,
    FAILED,
    ;

    internal companion object {
        /** AiSList's answers as its own clients read them; it publishes no API documentation. */
        fun fromStatus(code: Int): AiReportResult =
            when (code) {
                in 200..299 -> SENT
                409 -> BUSY
                429 -> RATE_LIMITED
                400 -> REJECTED
                else -> FAILED
            }
    }
}

/**
 * Reports a channel to AiSList, the list's own maintainers, with a video as evidence. Opt-in: it
 * sends the channel handle, the video link and the viewer's chosen submitter name, which AiSList
 * shows on its public leaderboard. Their reviewers decide; nothing changes on the device.
 */
@Singleton
class AiChannelReporter
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val aiList: AiListRepository,
        private val client: OkHttpClient,
    ) {
        val enabled: Flow<Boolean> = preferences.aiListReportEnabled

        suspend fun check(video: Video): AiReportCheck {
            if (preferences.aiListReportName.first().isBlank()) return AiReportCheck.NoName
            val handle = handleOf(video) ?: return AiReportCheck.NoHandle
            val listed = aiList.listing(video.channelId, handle)
            return if (listed == AiVerdict.NONE) AiReportCheck.Ready(handle) else AiReportCheck.AlreadyListed(listed)
        }

        suspend fun submit(
            handle: String,
            videoId: String,
        ): AiReportResult {
            val name = preferences.aiListReportName.first()
            if (name.isBlank()) return AiReportResult.REJECTED
            val body =
                buildJsonObject {
                    put("channel_handle", handle)
                    put("video_url", "https://youtu.be/$videoId")
                    put("username", name)
                }.toString()
            return withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(SUBMIT_URL)
                        .header("User-Agent", "Flow/${BuildConfig.VERSION_NAME}")
                        .cacheControl(CacheControl.Builder().noStore().build())
                        .post(body.toRequestBody(JSON))
                        .build()
                try {
                    client.newCall(request).execute().use { AiReportResult.fromStatus(it.code) }
                } catch (_: IOException) {
                    AiReportResult.FAILED
                }
            }
        }

        /** The card's handle when the feed sent one, otherwise the channel page's. */
        private suspend fun handleOf(video: Video): String? {
            video.channelHandle?.let(AiHandle::forReport)?.let { return it }
            if (!video.channelId.startsWith("UC")) return video.channelId.takeIf { it.startsWith('@') }?.let(AiHandle::forReport)
            val header = YouTube.channelLanding(video.channelId).getOrNull()?.header ?: return null
            return header.handle?.let(AiHandle::forReport)
        }

        private companion object {
            const val SUBMIT_URL = "https://api.aisloplist.com"
            val JSON = "application/json".toMediaType()
        }
    }
