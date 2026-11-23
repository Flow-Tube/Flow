package io.github.aedev.flow.utils

/** The list with the item keyed [fromKey] moved to where [toKey] is, or null when either key is missing. */
fun <T> List<T>.movedByKey(
    fromKey: Any,
    toKey: Any,
    key: (T) -> Any,
): List<T>? {
    val from = indexOfFirst { key(it) == fromKey }
    val to = indexOfFirst { key(it) == toKey }
    if (from < 0 || to < 0 || from == to) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** The list with the item at [index] moved to [target], or null when either is out of range or they match. */
fun <T> List<T>.movedTo(
    index: Int,
    target: Int,
): List<T>? {
    if (index !in indices || target !in indices || index == target) return null
    return toMutableList().apply { add(target, removeAt(index)) }
}

/**
 * True while the database still holds the order from before a drop: the same items as [pending],
 * the order just saved, in another order. A list with other items has moved on and is shown.
 */
fun holdsPendingOrder(
    saved: List<String>,
    pending: List<String>?,
): Boolean = pending != null && saved != pending && saved.size == pending.size && saved.toSet() == pending.toSet()
