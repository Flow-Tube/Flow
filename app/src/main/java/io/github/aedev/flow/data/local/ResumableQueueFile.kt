package io.github.aedev.flow.data.local

import java.io.File

/**
 * Whether the saved music queue can be resumed, answered from file metadata alone: a Play button that
 * starts the music service needs the answer on the main thread, and a yes that then fails to resume
 * leaves Media3 nothing to show, so Android kills the app for not going foreground (#2283).
 *
 * A store DataStore reset after corruption is left empty, and one that could not be read or decoded
 * carries a marker beside it until the next successful save.
 */
internal class ResumableQueueFile(
    private val store: File,
) {
    private val unreadableMarker: File
        get() = File(store.parentFile, "${store.name}.unreadable")

    fun canResume(): Boolean = store.length() > 0L && !unreadableMarker.exists()

    fun markReadable() {
        unreadableMarker.delete()
    }

    fun markUnreadable() {
        if (store.length() > 0L) runCatching { unreadableMarker.createNewFile() }
    }
}
