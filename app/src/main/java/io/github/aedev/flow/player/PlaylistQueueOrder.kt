package io.github.aedev.flow.player

import kotlin.random.Random

internal data class ReorderedQueue<T>(
    val items: List<T>,
    val currentIndex: Int,
)

internal data class QueueRemoval<T>(
    val queue: ReorderedQueue<T>,
    val removedItem: T,
)

internal object PlaylistQueueOrder {
    /** A queue whose later pages are still to come never wraps: its end isn't the end of the list. */
    fun nextIndex(
        itemCount: Int,
        currentIndex: Int,
        loopEnabled: Boolean,
        morePending: Boolean = false,
    ): Int? =
        when {
            currentIndex < itemCount - 1 -> currentIndex + 1
            loopEnabled && itemCount > 0 && !morePending -> 0
            else -> null
        }

    /**
     * [added] spread at random through the part of a shuffled queue not yet played. The video right
     * after the current one keeps its place, since it may already be preloaded.
     */
    fun <T> mixIn(
        items: List<T>,
        currentIndex: Int,
        added: List<T>,
        random: Random = Random.Default,
    ): List<T> {
        val result = items.toMutableList()
        val firstSlot = (currentIndex + 2).coerceIn(0, result.size)
        added.forEach { item -> result.add(random.nextInt(firstSlot, result.size + 1), item) }
        return result
    }

    fun <T> shuffleFromCurrent(
        items: List<T>,
        currentIndex: Int,
        random: Random = Random.Default,
    ): ReorderedQueue<T> {
        val currentItem = items.getOrNull(currentIndex) ?: return ReorderedQueue(items, currentIndex)
        val remainingItems = items.filterIndexed { index, _ -> index != currentIndex }.shuffled(random)
        return ReorderedQueue(listOf(currentItem) + remainingItems, currentIndex = 0)
    }

    fun <T, K> restoreOriginal(
        original: List<T>,
        currentItem: T?,
        keySelector: (T) -> K,
    ): ReorderedQueue<T> {
        if (currentItem == null) return ReorderedQueue(original, currentIndex = -1)
        val currentKey = keySelector(currentItem)
        val restoredIndex = original.indexOfFirst { keySelector(it) == currentKey }.coerceAtLeast(0)
        return ReorderedQueue(original, restoredIndex)
    }

    fun <T> removeAt(
        items: List<T>,
        currentIndex: Int,
        index: Int,
    ): QueueRemoval<T>? {
        if (index !in items.indices || index == currentIndex) return null

        val updatedItems = items.toMutableList()
        val removedItem = updatedItems.removeAt(index)
        val updatedCurrentIndex = if (index < currentIndex) currentIndex - 1 else currentIndex
        return QueueRemoval(
            queue = ReorderedQueue(updatedItems, updatedCurrentIndex),
            removedItem = removedItem,
        )
    }

    fun <T> move(
        items: List<T>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int,
    ): ReorderedQueue<T>? {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) return null

        val updatedItems = items.toMutableList()
        updatedItems.add(toIndex, updatedItems.removeAt(fromIndex))
        val updatedCurrentIndex =
            when {
                fromIndex == currentIndex -> toIndex
                fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
                fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
                else -> currentIndex
            }
        return ReorderedQueue(updatedItems, updatedCurrentIndex)
    }

    fun <T, K> removeMatching(
        items: List<T>,
        target: T,
        keySelector: (T) -> K,
    ): List<T> {
        val matchingIndex = indexMatching(items, target, keySelector)
        if (matchingIndex < 0) return items
        return items.toMutableList().apply { removeAt(matchingIndex) }
    }

    fun <T, K> indexMatching(
        items: List<T>,
        target: T,
        keySelector: (T) -> K,
    ): Int =
        items.indexOf(target).takeIf { it >= 0 }
            ?: items.indexOfFirst { keySelector(it) == keySelector(target) }

    /**
     * The `moveMediaItem(from, to)` calls, applied in order, that turn [current] into [target], or
     * null when the two don't hold the same items.
     */
    fun <K> movesTo(
        current: List<K>,
        target: List<K>,
    ): List<Pair<Int, Int>>? {
        if (current.groupingBy { it }.eachCount() != target.groupingBy { it }.eachCount()) return null
        val working = current.toMutableList()
        val moves = mutableListOf<Pair<Int, Int>>()
        target.forEachIndexed { toIndex, key ->
            val fromIndex = (toIndex until working.size).first { working[it] == key }
            if (fromIndex != toIndex) {
                working.add(toIndex, working.removeAt(fromIndex))
                moves += fromIndex to toIndex
            }
        }
        return moves
    }

    /** [items] put in the order of [order], or null when the two don't hold the same items. */
    fun <T, K> alignTo(
        items: List<T>,
        order: List<K>,
        keySelector: (T) -> K,
    ): List<T>? {
        if (items.size != order.size) return null
        val byKey = items.groupBy(keySelector).mapValues { (_, matches) -> ArrayDeque(matches) }
        return order.map { key -> byKey[key]?.removeFirstOrNull() ?: return null }
    }
}
