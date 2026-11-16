package io.github.aedev.flow.data.model

import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity

data class SubscriptionGroup(
    val name: String,
    val channelIds: List<String>,
    val sortOrder: Int = 0,
)

fun SubscriptionGroupEntity.toUiModel() =
    SubscriptionGroup(
        name = name,
        channelIds = if (channelIds.isBlank()) emptyList() else channelIds.split(",").filter { it.isNotBlank() },
        sortOrder = sortOrder,
    )

/** This group with [channelId] added or removed, keeping the other members in their stored order. */
fun SubscriptionGroupEntity.withMember(
    channelId: String,
    inGroup: Boolean,
): SubscriptionGroupEntity {
    val members = toUiModel().channelIds.toMutableList()
    members.remove(channelId)
    if (inGroup) members.add(channelId)
    return copy(channelIds = members.joinToString(","))
}
