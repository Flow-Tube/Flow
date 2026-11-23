package io.github.aedev.flow

import android.content.ComponentCallbacks2

/**
 * Android 14+ sends TRIM_MEMORY_UI_HIDDEN every time the app leaves the screen, so acting on it
 * emptied the playlist caches and closed the connection background audio was reusing on each exit.
 * BACKGROUND only arrives once the process is cached, which a running media service prevents.
 * Coil trims its own memory cache by level and is left alone here.
 */
internal object AppMemoryTrim {
    fun releasesVolatileMemory(trimLevel: Int): Boolean = trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}
