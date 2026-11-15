package io.github.aedev.flow.notification

import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val LOOKUP_BATCH = 4

/** What a new upload is, as far as a notification says: RSS lists streams and premieres like uploads. */
sealed interface NewVideoKind {
    data object Upload : NewVideoKind

    data object Live : NewVideoKind

    data class ScheduledStream(
        val startMs: Long,
    ) : NewVideoKind

    data class Premiere(
        val startMs: Long,
    ) : NewVideoKind

    companion object {
        /** From a player lookup's row; no row, or an upcoming one with no start time, stays an upload. */
        fun of(lookedUp: Video?): NewVideoKind {
            if (lookedUp == null) return Upload
            if (lookedUp.isUpcoming) {
                val start = lookedUp.timestamp.takeIf { it > 0 } ?: return Upload
                return if (lookedUp.isScheduledLive) ScheduledStream(start) else Premiere(start)
            }
            return if (lookedUp.isLive) Live else Upload
        }
    }
}

/**
 * The notification text for a new [title]: the title alone for an upload, with what it is in front
 * otherwise. [string] reads a resource with its arguments.
 */
internal fun NewVideoKind.text(
    title: String,
    startText: (Long) -> String,
    string: (Int, Array<Any>) -> String,
): String =
    when (this) {
        NewVideoKind.Upload -> title
        NewVideoKind.Live -> string(R.string.notification_new_video_live, arrayOf(title))
        is NewVideoKind.ScheduledStream -> string(R.string.notification_new_video_scheduled, arrayOf(startText(startMs), title))
        is NewVideoKind.Premiere -> string(R.string.notification_new_video_premiere, arrayOf(startText(startMs), title))
    }

/** The start time alone when it is today where the viewer is, otherwise its date and time. */
internal fun startTimeText(
    startMs: Long,
    nowMs: Long,
    zone: ZoneId,
    locale: Locale,
): String {
    val start = Instant.ofEpochMilli(startMs).atZone(zone)
    val sameDay = start.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val formatter =
        if (sameDay) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
    return formatter.withLocale(locale).format(start)
}

/** Each of [videos] with its kind, looked up [LOOKUP_BATCH] at a time; a failed lookup leaves an upload. */
internal suspend fun classifyNewVideos(
    videos: List<SubscriptionNotifications.NewVideoEntry>,
    dispatcher: CoroutineDispatcher,
    lookUp: suspend (SubscriptionNotifications.NewVideoEntry) -> Video?,
): List<SubscriptionNotifications.NewVideoEntry> =
    videos.chunked(LOOKUP_BATCH).flatMap { batch ->
        coroutineScope {
            batch
                .map { entry ->
                    async(dispatcher) {
                        val row =
                            try {
                                lookUp(entry)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        entry.copy(kind = NewVideoKind.of(row))
                    }
                }.awaitAll()
        }
    }
