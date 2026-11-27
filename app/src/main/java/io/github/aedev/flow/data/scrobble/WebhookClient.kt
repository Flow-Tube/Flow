package io.github.aedev.flow.data.scrobble

import android.util.Log
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Posts listens to the viewer's own addresses in Web Scrobbler's webhook format, one event per
 * request, so anything built for Web Scrobbler can read them.
 */
class WebhookClient(
    private val http: () -> OkHttpClient,
) {
    suspend fun nowPlaying(
        url: String,
        entry: ScrobbleEntry,
        nowMs: Long,
    ): SendOutcome = post(url, webhookEventJson(NOW_PLAYING, nowMs, entry))

    suspend fun scrobble(
        url: String,
        entry: ScrobbleEntry,
        nowMs: Long,
    ): SendOutcome = post(url, webhookEventJson(SCROBBLE, nowMs, entry))

    suspend fun setLoved(
        url: String,
        love: LoveEntry,
        nowMs: Long,
    ): SendOutcome = post(url, webhookEventJson(LOVED, nowMs, love.asEntry(), isLoved = love.loved))

    private suspend fun post(
        url: String,
        event: JsonObject,
    ): SendOutcome {
        val address = url.toHttpUrlOrNull() ?: return SendOutcome.Sent
        return withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(address)
                    .post(event.toString().toRequestBody(JSON))
                    .build()
            try {
                http().newCall(request).execute().use { response -> webhookOutcome(response.code) }
            } catch (e: IOException) {
                Log.w(TAG, "webhook failed: ${e.message}")
                SendOutcome.Retry
            }
        }
    }

    companion object {
        const val NOW_PLAYING = "nowplaying"
        const val SCROBBLE = "scrobble"
        const val LOVED = "loved"
        private const val TAG = "ScrobbleWebhook"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** An address OkHttp can post to: http or https with a host. */
fun isWebhookAddress(url: String): Boolean = url.trim().toHttpUrlOrNull() != null

/**
 * Rate limits and server errors are tried again later. Any other refusal drops the event, since
 * sending the same body again gets the same answer; the webhook itself is never removed.
 */
internal fun webhookOutcome(code: Int): SendOutcome =
    when {
        code == TOO_MANY_REQUESTS || code >= SERVER_ERROR -> SendOutcome.Retry
        else -> SendOutcome.Sent
    }

/** Web Scrobbler's envelope: the event name, when it was sent in ms, and the song as its connectors parse it. */
internal fun webhookEventJson(
    eventName: String,
    timeMs: Long,
    entry: ScrobbleEntry,
    isLoved: Boolean? = null,
): JsonObject =
    buildJsonObject {
        put("eventName", eventName)
        put("time", timeMs)
        put(
            "data",
            buildJsonObject {
                put("song", webhookSongJson(entry))
                if (isLoved != null) put("isLoved", isLoved)
            },
        )
    }

private fun webhookSongJson(entry: ScrobbleEntry): JsonObject {
    val fromYouTube = entry.fromYouTube && entry.videoId.isNotBlank()
    val album = entry.album.takeIf { it.isNotBlank() }
    val duration = entry.durationSec.takeIf { it > 0 }
    return buildJsonObject {
        put(
            "parsed",
            buildJsonObject {
                put("track", entry.title)
                put("artist", entry.artist)
                put("album", album)
                put("albumArtist", JsonNull)
                put("duration", duration)
                put("trackArt", entry.thumbnailUrl.takeIf { it.isNotBlank() })
                put("uniqueID", entry.videoId.takeIf { fromYouTube })
                put("originUrl", if (fromYouTube) "$WATCH_URL${entry.videoId}" else null)
                put("isPodcast", false)
            },
        )
        put(
            "processed",
            buildJsonObject {
                put("track", entry.title)
                put("artist", entry.artist)
                put("album", album)
                put("albumArtist", JsonNull)
                put("duration", duration)
            },
        )
        put(
            "metadata",
            buildJsonObject {
                if (entry.timestampSec > 0) put("startTimestamp", entry.timestampSec)
            },
        )
        put("flags", JsonNull)
        put("connectorLabel", CONNECTOR_LABEL)
    }
}

private fun LoveEntry.asEntry() =
    ScrobbleEntry(
        artist = artist,
        title = title,
        timestampSec = 0,
        fromYouTube = videoId.isNotBlank() && !LocalMediaIds.isLocal(videoId),
        videoId = videoId,
    )

private const val TOO_MANY_REQUESTS = 429
private const val SERVER_ERROR = 500
private const val CONNECTOR_LABEL = "Flow"
private const val WATCH_URL = "https://music.youtube.com/watch?v="
