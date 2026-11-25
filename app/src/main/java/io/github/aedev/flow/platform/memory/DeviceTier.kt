package io.github.aedev.flow.platform.memory

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.config.PlayerConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device's app heap is small (#2204): under 192 MB, as on 128 MB TVs, or a device
 * Android flags as low-RAM. Read once; the heap size of a process never changes.
 */
@Singleton
class DeviceTier
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val isSmallHeap: Boolean =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager).let { manager ->
                isSmall(memoryClassMb = manager?.memoryClass ?: UNKNOWN_MEMORY_CLASS_MB, isLowRamDevice = manager?.isLowRamDevice == true)
            }

        /** Watch responses kept for the videos in play: the playing one alone on a small heap. */
        val watchCacheEntries: Int get() = if (isSmallHeap) SMALL_HEAP_WATCH_CACHE_ENTRIES else WATCH_CACHE_ENTRIES

        companion object {
            const val WATCH_CACHE_ENTRIES = 3
            const val SMALL_HEAP_WATCH_CACHE_ENTRIES = 1
            private const val UNKNOWN_MEMORY_CLASS_MB = 256

            fun isSmall(
                memoryClassMb: Int,
                isLowRamDevice: Boolean,
            ): Boolean = isLowRamDevice || memoryClassMb < PlayerConfig.SMALL_HEAP_MB
        }
    }
