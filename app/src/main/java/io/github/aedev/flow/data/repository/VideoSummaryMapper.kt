package io.github.aedev.flow.data.repository

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import java.time.OffsetDateTime

/**
 * One video as a playlist or history repair needs it, from the player response's details and
 * microformat plus the watch response's owner avatar. The upload date stays the microformat's ISO
 * text, which is what these rows have always stored.
 */
internal object VideoSummaryMapper {
    fun toVideo(
        videoId: String,
        player: PlayerResponse,
        watch: WatchMetadataResponse?,
        relativeTimestamp: (String?) -> Long,
    ): Video? {
        val details = player.videoDetails ?: return null
        val microformat = player.microformat?.playerMicroformatRenderer
        val thumbnail =
            details.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.height ?: 0 }
                ?.url
                ?.takeIf { it.isNotBlank() }
        val uploadDate =
            microformat?.uploadDate?.takeIf { it.isNotBlank() }
                ?: microformat?.publishDate?.takeIf { it.isNotBlank() }
                ?: watch?.uploadDate()?.takeIf { it.isNotBlank() }
        val avatar = watch?.channelAvatarUrl()?.takeIf { it.isNotBlank() }
        return Video(
            id = videoId,
            title = details.title?.takeIf { it.isNotBlank() } ?: "Unknown Title",
            channelName = details.author?.takeIf { it.isNotBlank() } ?: "Unknown Channel",
            channelId = details.channelId,
            thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnail),
            duration = details.lengthSeconds.toIntOrNull() ?: 0,
            viewCount = details.viewCount?.toLongOrNull() ?: microformat?.viewCount?.toLongOrNull() ?: -1L,
            uploadDate = uploadDate ?: "Unknown",
            timestamp = isoMillis(uploadDate) ?: relativeTimestamp(uploadDate),
            channelThumbnailUrl = avatar.orEmpty(),
            channelThumbnailUrls = listOfNotNull(avatar),
            category = microformat?.category?.takeIf { it.isNotBlank() },
        )
    }

    private fun isoMillis(text: String?): Long? =
        text?.let { value ->
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .recoverCatching {
                    java.time.LocalDate
                        .parse(value)
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
        }
}
