package io.github.aedev.flow.platform.memory

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Sizes of the app's larger in-memory holdings, read only when [HeapSampler] writes a breadcrumb. */
@Singleton
class MemoryGauges
    @Inject
    constructor() {
        private val readers = ConcurrentHashMap<String, () -> Int>()

        fun register(
            name: String,
            read: () -> Int,
        ) {
            readers[name] = read
        }

        /** Removes [read] only if it is still the one registered, so a newer owner of [name] keeps its gauge. */
        fun unregister(
            name: String,
            read: () -> Int,
        ) {
            readers.remove(name, read)
        }

        fun snapshot(): Map<String, Int> =
            readers.entries.associate { (name, read) -> name to (runCatching(read).getOrNull() ?: -1) }.toSortedMap()
    }
