package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.model.SubscriptionGroup

/** The Ungrouped chip's selection value and row name. No typed group name can hold a NUL. */
internal const val UNGROUPED_GROUP_KEY = "\u0000ungrouped"

/** Stored as Ungrouped's position when it sits after every group, so groups added later stay before it. */
internal const val UNGROUPED_LAST = -1

internal fun SubscriptionGroup.isUngrouped(): Boolean = name == UNGROUPED_GROUP_KEY

/** Subscribed channels that are in no group, in subscription order. */
internal fun ungroupedChannelIds(
    subscribedIds: Collection<String>,
    groups: List<SubscriptionGroup>,
): Set<String> {
    val grouped = groups.flatMapTo(HashSet()) { it.channelIds }
    return subscribedIds.filterNotTo(LinkedHashSet()) { it in grouped }
}

/** [groups] with the Ungrouped entry at [position] among them, or last when [position] is [UNGROUPED_LAST] or past the end. */
internal fun groupsWithUngrouped(
    groups: List<SubscriptionGroup>,
    position: Int,
    ungroupedIds: Set<String>,
): List<SubscriptionGroup> {
    val index = if (position in 0..groups.size) position else groups.size
    return groups.toMutableList().apply { add(index, SubscriptionGroup(UNGROUPED_GROUP_KEY, ungroupedIds.toList())) }
}

/** A reordered list split back into the real group order and Ungrouped's position, null when it was not in the list. */
internal fun splitUngroupedOrder(names: List<String>): Pair<List<String>, Int?> {
    val groupNames = names.filter { it != UNGROUPED_GROUP_KEY }
    val index = names.indexOf(UNGROUPED_GROUP_KEY)
    val position =
        when {
            index < 0 -> null
            index == groupNames.size -> UNGROUPED_LAST
            else -> index
        }
    return groupNames to position
}

/** The channels [selected] covers; null for every subscription, including a group that no longer exists. */
internal fun groupSelectionScope(
    selected: String?,
    groups: List<SubscriptionGroup>,
    subscribedIds: Collection<String>,
): Set<String>? =
    when (selected) {
        null -> null
        UNGROUPED_GROUP_KEY -> ungroupedChannelIds(subscribedIds, groups)
        else -> groups.find { it.name == selected }?.channelIds?.toSet()
    }
