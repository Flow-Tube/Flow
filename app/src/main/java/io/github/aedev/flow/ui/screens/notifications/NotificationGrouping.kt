package io.github.aedev.flow.ui.screens.notifications

import io.github.aedev.flow.data.local.entity.NotificationEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val LAST_WEEK_DAYS = 6L

/** The inbox's sections, in the order they are shown. */
internal enum class NotificationBucket { NEW, TODAY, YESTERDAY, LAST_WEEK, EARLIER }

internal data class NotificationSection(
    val bucket: NotificationBucket,
    val items: List<NotificationEntity>,
)

/** When a notification arrived, in the form its row shows it. */
internal sealed interface NotificationTime {
    data class Relative(
        val timestampMs: Long,
    ) : NotificationTime

    data class TimeOfDay(
        val time: LocalTime,
    ) : NotificationTime

    data class Weekday(
        val day: DayOfWeek,
    ) : NotificationTime

    data class Date(
        val date: LocalDate,
    ) : NotificationTime
}

/** The calendar-day section for [timestampMs]; anything stamped after [today] still counts as today. */
internal fun dayBucket(
    timestampMs: Long,
    today: LocalDate,
    zone: ZoneId,
): NotificationBucket {
    val day = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> NotificationBucket.TODAY
        day == today.minusDays(1) -> NotificationBucket.YESTERDAY
        !day.isBefore(today.minusDays(LAST_WEEK_DAYS)) -> NotificationBucket.LAST_WEEK
        else -> NotificationBucket.EARLIER
    }
}

/**
 * Splits [items] (newest first) into sections: everything in [newIds] first, then the rest by
 * calendar day. Order inside a section is kept.
 */
internal fun groupNotifications(
    items: List<NotificationEntity>,
    newIds: Set<Int>,
    today: LocalDate,
    zone: ZoneId,
): List<NotificationSection> {
    val (fresh, seen) = items.partition { it.id in newIds }
    return buildList {
        if (fresh.isNotEmpty()) add(NotificationSection(NotificationBucket.NEW, fresh))
        seen
            .groupBy { dayBucket(it.timestamp, today, zone) }
            .toSortedMap()
            .forEach { (bucket, sectionItems) -> add(NotificationSection(bucket, sectionItems)) }
    }
}

internal fun notificationTime(
    timestampMs: Long,
    today: LocalDate,
    zone: ZoneId,
): NotificationTime {
    val moment = Instant.ofEpochMilli(timestampMs).atZone(zone)
    return when (dayBucket(timestampMs, today, zone)) {
        NotificationBucket.NEW, NotificationBucket.TODAY -> NotificationTime.Relative(timestampMs)
        NotificationBucket.YESTERDAY -> NotificationTime.TimeOfDay(moment.toLocalTime())
        NotificationBucket.LAST_WEEK -> NotificationTime.Weekday(moment.dayOfWeek)
        NotificationBucket.EARLIER -> NotificationTime.Date(moment.toLocalDate())
    }
}

/** A row of a section: one notification, or the uploads of one channel shown together. */
internal sealed interface InboxRow {
    val key: String

    data class Single(
        val notification: NotificationEntity,
    ) : InboxRow {
        override val key: String get() = "n${notification.id}"
    }

    /** Newest first, as the section had them. */
    data class Channel(
        val bucket: NotificationBucket,
        val channelName: String,
        val notifications: List<NotificationEntity>,
    ) : InboxRow {
        override val key: String get() = "c:$bucket:$channelName"
    }
}

/**
 * [items] (newest first) with each channel's new-upload notifications, when there are two or more,
 * gathered into one row where its newest one was. Everything else keeps its place.
 */
internal fun groupByChannel(
    bucket: NotificationBucket,
    items: List<NotificationEntity>,
): List<InboxRow> {
    val uploads = items.filter { it.groupsByChannel() }.groupBy { it.channelName }
    val placed = HashSet<String>()
    return items.mapNotNull { item ->
        val siblings = uploads[item.channelName].takeIf { item.groupsByChannel() }.orEmpty()
        when {
            siblings.size < 2 -> InboxRow.Single(item)
            placed.add(item.channelName) -> InboxRow.Channel(bucket, item.channelName, siblings)
            else -> null
        }
    }
}

private fun NotificationEntity.groupsByChannel() = type == "NEW_VIDEO" && channelName.isNotBlank()

/** What the list draws for a section: channel rows, and under an open one its uploads. */
internal sealed interface InboxLine {
    val key: String

    data class Header(
        val row: InboxRow.Channel,
        val expanded: Boolean,
    ) : InboxLine {
        override val key: String get() = row.key
    }

    data class Item(
        val notification: NotificationEntity,
        override val key: String,
    ) : InboxLine
}

internal fun inboxLines(
    rows: List<InboxRow>,
    expanded: Set<String>,
): List<InboxLine> =
    rows.flatMap { row ->
        when (row) {
            is InboxRow.Single -> {
                listOf(InboxLine.Item(row.notification, row.key))
            }

            is InboxRow.Channel -> {
                val open = row.key in expanded
                val header = InboxLine.Header(row, open)
                if (open) listOf(header) + row.notifications.map { InboxLine.Item(it, "${row.key}/${it.id}") } else listOf(header)
            }
        }
    }
