package io.github.aedev.flow.service

import android.content.Context
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Whether a Play button on headphones may start the saved music queue after Flow was closed (#1450).
 * It is off after Close, after a video plays, and while the setting is off; music playing with the
 * setting on turns it back on. Media3 asks in `onGetSession`, on the main thread of a process the
 * button just started and before a DataStore read could finish, so the answer is a marker file.
 */
internal object HeadphoneResume {
    private const val MARKER = "headphone_resume_off"

    // One writer at a time, so an allow and a block issued in order land in that order.
    private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.diskIO.limitedParallelism(1))

    fun isAllowed(context: Context): Boolean = !marker(context).exists()

    fun set(
        context: Context,
        allowed: Boolean,
    ) {
        val file = marker(context.applicationContext)
        scope.launch { if (allowed) file.delete() else file.createNewFile() }
    }

    private fun marker(context: Context) = File(context.noBackupFilesDir, MARKER)
}
