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

/**
 * Swaps the items around the current one; the current item, and its playback, are left alone. Every
 * queue edit rebuilds the whole queue for the system session, so this never goes item by item (#2378).
 */
internal fun Player.replaceAround(
    before: List<MediaItem>,
    after: List<MediaItem>,
) {
    if (mediaItemCount == 0) {
        addMediaItems(before + after)
        return
    }
    val index = currentMediaItemIndex.coerceAtLeast(0)
    if (index + 1 < mediaItemCount || after.isNotEmpty()) replaceMediaItems(index + 1, mediaItemCount, after)
    if (index > 0 || before.isNotEmpty()) replaceMediaItems(0, index, before)
}

/** Puts the player's own items in [order], a list of their indices that holds the current one. */
internal fun Player.reorderAround(order: List<Int>) {
    val at = order.indexOf(currentMediaItemIndex)
    if (at < 0 || order.size != mediaItemCount) return
    val items = order.map(::getMediaItemAt)
    replaceAround(items.subList(0, at), items.subList(at + 1, items.size))
}
