package io.github.aedev.flow.ui.screens.playlists

import java.time.Duration
import java.time.Instant

/** How long a check of a saved playlist against YouTube stays trusted before opening it checks again. */
internal val SavedPlaylistRefreshTtl: Duration = Duration.ofHours(6)

/** A check younger than [SavedPlaylistRefreshTtl]; a check stamped in the future (clock moved back) is not trusted. */
internal fun isSavedCopyFresh(
    lastCheckedAt: Instant?,
    now: Instant,
): Boolean = lastCheckedAt != null && !now.isBefore(lastCheckedAt) && Duration.between(lastCheckedAt, now) < SavedPlaylistRefreshTtl

/**
 * Whether the first page shows the saved copy is current, so no other page needs loading. A page
 * with nothing after it is the whole list and must match it exactly. Otherwise its videos must open
 * the saved list in the same order, and the header's count must equal the saved count or the count
 * the header gave at the last check (the header counts unavailable videos the list leaves out).
 */
internal fun firstPageMatchesSavedCopy(
    savedIds: List<String>,
    firstPageIds: List<String>,
    hasMorePages: Boolean,
    headerCount: Int?,
    lastHeaderCount: Int?,
): Boolean {
    val firstIds = firstPageIds.distinct()
    if (!hasMorePages) return firstIds == savedIds
    val countMatches = headerCount != null && (headerCount == savedIds.size || headerCount == lastHeaderCount)
    return countMatches && firstIds.isNotEmpty() && firstIds == savedIds.take(firstIds.size)
}
