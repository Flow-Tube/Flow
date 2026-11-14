package io.github.aedev.flow.data.shorts.feed

internal data class ShortsFeedAssembly(
    val page: List<ShortsLaneItem>,
    /** Every id the assembly took out of the pools: placed on the page, or dropped by a filter. */
    val consumed: Set<String>,
    val laneCounts: Map<ShortsFeedLane, Int>,
)

/**
 * One page out of the lane pools: filters by id, then blends under the quotas and spaces channels.
 *
 * The seen gate follows the engine's rule for the long-form feed: it is skipped while the pools
 * are thin, and backed off when it would leave too little, so a heavily watched account is never
 * refreshed into an empty feed. Watched, suppressed and excluded reels are always dropped.
 */
internal fun assembleShortsPage(
    pools: Map<ShortsFeedLane, List<ShortsLaneItem>>,
    quotas: Map<ShortsFeedLane, Int>,
    targetSize: Int,
    filters: ShortsFeedFilters,
    usedIds: Set<String>,
    recentChannels: List<String>,
): ShortsFeedAssembly {
    val consumed = mutableSetOf<String>()
    val hardFiltered =
        pools.mapValues { (_, items) ->
            items.filter { item ->
                val short = item.short
                val keep =
                    short.id !in usedIds &&
                        short.id !in filters.watchedIds &&
                        short.id !in filters.suppressedIds &&
                        (short.channelId.isBlank() || short.channelId !in filters.excludedChannelIds) &&
                        !filters.isBlockedText(short.title, short.channelName) &&
                        !filters.isAiBlocked(short.channelId)
                if (!keep) consumed += short.id
                keep
            }
        }

    val candidateCount = hardFiltered.values.sumOf { it.size }
    val seenFiltered =
        if (candidateCount < SEEN_GATE_MIN_POOL) {
            hardFiltered
        } else {
            val gated = hardFiltered.mapValues { (_, items) -> items.filter { it.short.id !in filters.seenIds } }
            if (gated.values.sumOf { it.size } < SEEN_GATE_MIN_RESULTS) hardFiltered else gated
        }
    if (seenFiltered !== hardFiltered) {
        hardFiltered.values.flatten().forEach { item -> if (item.short.id in filters.seenIds) consumed += item.short.id }
    }

    val mix = blendShortsLanes(seenFiltered, quotas, targetSize)
    val page = spaceShortsByChannel(mix.items, seedRecent = recentChannels)
    page.forEach { consumed += it.short.id }
    return ShortsFeedAssembly(page = page, consumed = consumed, laneCounts = mix.laneCounts)
}

private const val SEEN_GATE_MIN_POOL = 25
private const val SEEN_GATE_MIN_RESULTS = 10
