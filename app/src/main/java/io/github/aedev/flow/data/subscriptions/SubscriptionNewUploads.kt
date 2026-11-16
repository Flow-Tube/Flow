package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.model.Video

/** When each subscribed channel's page was last opened, kept in one preference as `id=millis` pairs (#1755). */
internal fun parseChannelSeenAt(stored: String?): Map<String, Long> =
    stored
        .orEmpty()
        .split(',')
        .mapNotNull { entry ->
            val id = entry.substringBefore('=', "").trim()
            val seenAt = entry.substringAfter('=', "").trim().toLongOrNull()
            if (id.isEmpty() || seenAt == null) null else id to seenAt
        }.toMap()

internal fun Map<String, Long>.toChannelSeenStorage(): String = entries.joinToString(",") { (id, seenAt) -> "$id=$seenAt" }

/** [stored] with [channelId] seen at [now], keeping only the channels in [subscribed] so the map can't grow forever. */
internal fun markChannelSeen(
    stored: String,
    channelId: String,
    now: Long,
    subscribed: Set<String>,
): String = (parseChannelSeenAt(stored) + (channelId to now)).filterKeys { it in subscribed }.toChannelSeenStorage()

/**
 * The channels with an upload newer than both [since] and the last visit to the channel's page, that the viewer
 * hasn't started. [videos] are the feed rows the Videos, Shorts and Live switches let through; a premiere or
 * scheduled stream counts once it starts.
 */
internal fun channelsWithNewUploads(
    videos: List<Video>,
    seenAt: Map<String, Long>,
    since: Long,
    startedIds: Set<String>,
): Set<String> {
    if (since <= 0L) return emptySet()
    return videos
        .asSequence()
        .filter { !it.isUpcoming && it.id !in startedIds }
        .filter { it.timestamp > maxOf(since, seenAt[it.channelId] ?: 0L) }
        .mapTo(HashSet()) { it.channelId }
}
