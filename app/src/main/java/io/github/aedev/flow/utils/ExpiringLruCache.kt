package io.github.aedev.flow.utils

import androidx.collection.LruCache

/**
 * An LRU whose entries each carry their own lifetime. [fresh] answers only while an entry is in date and
 * drops it once it is not, so a caller deciding whether to fetch again sees the same lifetime the data has.
 */
class ExpiringLruCache<K : Any, V : Any>(
    capacity: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(
        val value: V,
        val expiresAtMs: Long,
    )

    private val entries = LruCache<K, Entry<V>>(capacity)

    fun put(
        key: K,
        value: V,
        ttlMs: Long,
    ) {
        entries.put(key, Entry(value, now() + ttlMs))
    }

    /** [key]'s value while it is in date. */
    fun fresh(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() < entry.expiresAtMs) return entry.value
        entries.remove(key)
        return null
    }

    /** [key]'s value at any age, for what does not go stale with it. */
    fun stored(key: K): V? = entries[key]?.value

    fun remove(key: K) {
        entries.remove(key)
    }

    fun clear() = entries.evictAll()

    fun size(): Int = entries.size()
}
