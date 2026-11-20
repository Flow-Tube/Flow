package io.github.aedev.flow.player.shorts

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

/** Whether a trim level asks the Shorts pool to drop the reels that are not on screen. */
@Suppress("DEPRECATION")
internal fun shouldTrimShortsNeighbours(level: Int): Boolean =
    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

/** Forwards real memory pressure, not a merely hidden UI, to [onTrim] while registered on [context]. */
internal class ShortsMemoryTrim(
    private val context: Context,
    private val onTrim: () -> Unit,
) : ComponentCallbacks2 {
    fun register() = context.registerComponentCallbacks(this)

    fun unregister() = context.unregisterComponentCallbacks(this)

    override fun onTrimMemory(level: Int) {
        if (shouldTrimShortsNeighbours(level)) onTrim()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = onTrim()
}
