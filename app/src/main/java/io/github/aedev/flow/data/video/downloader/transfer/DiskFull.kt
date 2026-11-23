package io.github.aedev.flow.data.video.downloader.transfer

import android.system.ErrnoException
import android.system.OsConstants

/** Whether [error], or anything it wraps, is the storage running out. */
internal object DiskFull {
    fun isCause(error: Throwable): Boolean =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.any { cause ->
            (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) ||
                cause.message?.let { "ENOSPC" in it || "No space left" in it } == true
        }
}
