package io.github.aedev.flow.data.subscriptions

import io.github.aedev.flow.data.model.SubscriptionGroup

/**
 * The channels the All chip shows, or null for every subscription (#1387).
 *
 * A channel is left out only when every group holding it is hidden or kept out of All, so a channel
 * also in a group that All still covers stays, and a channel in no group always stays.
 */
fun allChipScope(
    subscribedIds: Collection<String>,
    groups: List<SubscriptionGroup>,
    hiddenGroups: Set<String>,
    groupsOutOfAll: Set<String>,
): Set<String>? {
    val excluded = hiddenGroups + groupsOutOfAll
    if (excluded.isEmpty() || groups.none { it.name in excluded }) return null
    val (left, kept) = groups.partition { it.name in excluded }
    val keptIds = kept.flatMapTo(HashSet()) { it.channelIds }
    val leftOut = left.flatMapTo(HashSet()) { it.channelIds }.filterNotTo(HashSet()) { it in keptIds }
    if (leftOut.isEmpty()) return null
    return subscribedIds.filterNotTo(LinkedHashSet()) { it in leftOut }
}
