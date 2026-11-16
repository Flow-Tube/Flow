package io.github.aedev.flow.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * A new queue split around the item playing: what goes before it and after it. When [items] doesn't
 * hold it, it stays and all of [items] follows it.
 */
internal fun <T> aroundCurrent(
    items: List<T>,
    isCurrent: (T) -> Boolean,
): Pair<List<T>, List<T>> {
    val at = items.indexOfFirst(isCurrent)
    return if (at < 0) emptyList<T>() to items else items.subList(0, at) to items.subList(at + 1, items.size)
}

/** Swaps the items around the current one; the current item, and its playback, are left alone. */
internal fun Player.replaceAround(
    before: List<MediaItem>,
    after: List<MediaItem>,
) {
    val index = currentMediaItemIndex.coerceAtLeast(0)
    if (index + 1 < mediaItemCount) removeMediaItems(index + 1, mediaItemCount)
    if (index > 0) removeMediaItems(0, index)
    addMediaItems(0, before)
    addMediaItems(after)
}
