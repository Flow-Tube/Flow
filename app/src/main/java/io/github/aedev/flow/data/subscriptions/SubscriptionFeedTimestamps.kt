package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.formatYouTubeRelativeTime
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser

/**
 * Upload-time reconciliation for subscription items.
 *
 * The feed merges two sources with different date quality: RSS carries a real publish timestamp,
 * while channel tabs often carry only relative text ("2 days ago") and sometimes a placeholder that
 * is simply "now". These helpers decide which of the two to trust so an item does not jump to the
 * top of the feed every refresh.
 */
object SubscriptionFeedTimestamps {
    /** How close to "now" a timestamp must be before it is suspected of being a placeholder. */
    private const val SUSPICIOUS_FRESH_TIMESTAMP_MS = 5L * 60L * 1000L

    fun effectiveUploadTimestamp(
        video: Video,
        now: Long,
    ): Long {
        val parsedRelative = parseRelativeTime(video.uploadDate, now)
        val timestamp = video.timestamp
        val timestampLooksLikeFallbackNow =
            timestamp in (now - SUSPICIOUS_FRESH_TIMESTAMP_MS)..(now + SUSPICIOUS_FRESH_TIMESTAMP_MS)
        val relativeDateIsClearlyOlder =
            parsedRelative != null && parsedRelative < now - SUSPICIOUS_FRESH_TIMESTAMP_MS

        return when {
            timestamp <= 0L -> parsedRelative ?: 0L
            timestampLooksLikeFallbackNow && isUnstableFreshUploadText(video.uploadDate) -> parsedRelative ?: 0L
            timestampLooksLikeFallbackNow && relativeDateIsClearlyOlder -> parsedRelative ?: timestamp
            else -> timestamp
        }
    }

    fun hasStableUploadMetadata(
        video: Video,
        now: Long,
    ): Boolean {
        if (video.isLive || video.isUpcoming) return true
        if (video.timestamp <= 0L) return false
        val timestampLooksLikeFallbackNow =
            video.timestamp in (now - SUSPICIOUS_FRESH_TIMESTAMP_MS)..(now + SUSPICIOUS_FRESH_TIMESTAMP_MS)
        if (timestampLooksLikeFallbackNow && isUnstableFreshUploadText(video.uploadDate)) {
            return false
        }
        return true
    }

    fun isUnstableFreshUploadText(value: String): Boolean {
        val text = StreamedUploadDate.unmarked(value).lowercase()
        if (text.isBlank() || text == "unknown" || text == "just now" || text == "today") return true
        return text.matches(Regex("""\d+\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours)(\s+ago)?"""))
    }

    fun parseRelativeTime(
        dateString: String,
        now: Long,
    ): Long? {
        val text = StreamedUploadDate.unmarked(dateString).lowercase()
        if (text.isBlank() || text == "unknown") return null
        if (text.contains("scheduled") || text.contains("premiere")) return now + 86400000L
        if (text.contains("live")) return now + 3600000L // Boost live streams
        return RelativeUploadDateParser.parse(text, YouTube.locale.hl, now)
    }
}

private data class SortableVideo(
    val video: Video,
    val uploadTimestamp: Long,
)

fun List<Video>.withStableUploadSortKeys(now: Long): List<Video> =
    map { video -> SortableVideo(video, SubscriptionFeedTimestamps.effectiveUploadTimestamp(video, now)) }
        .sortedWith(
            compareByDescending<SortableVideo> { it.uploadTimestamp }
                .thenByDescending { it.video.viewCount }
                .thenBy { it.video.id },
        ).map { it.video }

fun List<Video>.withRelativeUploadDates(now: Long): List<Video> =
    map { video ->
        val uploadTimestamp = SubscriptionFeedTimestamps.effectiveUploadTimestamp(video, now)
        val isFutureUpcoming = video.isUpcoming && uploadTimestamp > now + 60_000L
        if (isFutureUpcoming) {
            video.copy(isUpcoming = true)
        } else if (uploadTimestamp > 0L) {
            video.copy(
                uploadDate = video.keepingStreamedMark(formatYouTubeRelativeTime(uploadTimestamp, now)),
                isUpcoming = false,
            )
        } else {
            video.copy(
                uploadDate =
                    video.uploadDate
                        .takeUnless { SubscriptionFeedTimestamps.isUnstableFreshUploadText(it) }
                        .orEmpty(),
                isUpcoming = false,
            )
        }
    }

/** [recomputed] with the "Streamed" mark this row's stored date carried, unless it is on air again. */
internal fun Video.keepingStreamedMark(recomputed: String): String =
    StreamedUploadDate.markedIf(!isLive && StreamedUploadDate.isMarked(uploadDate), recomputed)

fun List<Video>.withHighQualityThumbnails(): List<Video> =
    map { video ->
        video.copy(
            thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(video.id, video.thumbnailUrl),
        )
    }
