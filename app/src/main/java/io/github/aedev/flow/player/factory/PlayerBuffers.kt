package io.github.aedev.flow.player.factory

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.Collections
import java.util.WeakHashMap

/** The allocators of live players, weakly held, so a heap breadcrumb can say how much media they hold. */
@UnstableApi
internal object PlayerBuffers {
    private val allocators: MutableSet<DefaultAllocator> = Collections.newSetFromMap(WeakHashMap())

    fun track(allocator: DefaultAllocator): DefaultAllocator = allocator.also { synchronized(allocators) { allocators.add(it) } }

    fun heldBytes(): Long = synchronized(allocators) { allocators.sumOf { it.totalBytesAllocated.toLong() } }
}
