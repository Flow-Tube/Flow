package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a download fits before it starts. Parts and the joined file sit side by side until the
 * join is done, so the parts still to fetch plus a whole output must fit, with 50 MiB to spare (the
 * reserve YouTube's offline store keeps). The system is asked to clear other apps' caches first.
 */
@Singleton
class DownloadSpace
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val storage = context.getSystemService(StorageManager::class.java)

        /** How many bytes [directory]'s volume is short of [bytes] plus the reserve; 0 when it fits. */
        fun shortBy(
            directory: File,
            bytes: Long,
        ): Long {
            val needed = bytes + RESERVE_BYTES
            return try {
                val volume = storage.getUuidForPath(directory)
                val allocatable = storage.getAllocatableBytes(volume)
                if (allocatable < needed) return needed - allocatable
                storage.allocateBytes(volume, needed)
                0L
            } catch (e: IOException) {
                Log.w(TAG, "Allocation check failed for $directory; using free space", e)
                (needed - directory.usableSpace).coerceAtLeast(0L)
            }
        }

        internal companion object {
            private const val TAG = "DownloadSpace"
            const val RESERVE_BYTES = 50L * 1024 * 1024
        }
    }
