package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.model.Channel

/** The pinned channel ids kept in one preference, in pin order (#1776). */
internal fun parsePinnedChannels(stored: String?): List<String> =
    stored
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()

internal fun List<String>.toPinnedStorage(): String = joinToString(",")

/** [pins] with [channelId] added at the end, or removed when it was already pinned. */
internal fun togglePinned(
    pins: List<String>,
    channelId: String,
): List<String> = if (channelId in pins) pins - channelId else pins + channelId

/** [all] with the pins in [shown] put in that order, in the slots they held; pins not in [shown] stay put. */
internal fun reorderPinnedSubset(
    all: List<String>,
    shown: List<String>,
): List<String> {
    val shownSet = shown.toHashSet()
    val next = shown.filter { it in all }.iterator()
    return all.map { id -> if (id in shownSet && next.hasNext()) next.next() else id }
}

/** The Quick access row: the pinned channels come first. */
internal class QuickAccessChannels(
    val channels: List<Channel>,
    val pinnedCount: Int,
)

/**
 * Pinned channels lead in pin order and always show; the rest follow [quickAccessOrder] up to [limit] in all. A pin
 * for a channel no longer subscribed is skipped.
 */
internal fun quickAccessChannels(
    sorted: List<Channel>,
    pinnedIds: List<String>,
    limit: Int,
): QuickAccessChannels {
    val byId = sorted.associateBy(Channel::id)
    val pinned = pinnedIds.mapNotNull(byId::get)
    val pinnedSet = pinned.mapTo(HashSet(), Channel::id)
    val rest = quickAccessOrder(sorted.filterNot { it.id in pinnedSet }).take((limit - pinned.size).coerceAtLeast(0))
    return QuickAccessChannels(pinned + rest, pinned.size)
}
