package io.github.aedev.flow.data.video.downloader.tags

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Keeps a 360° video 360° after the remux: reads the downloaded stream's projection and writes it into the MP4. */
@OptIn(UnstableApi::class)
class Mp4ProjectionCopier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Whether [output] was marked as 360°. Blocks while it reads [videoPath]'s header, so call it off the main thread. */
        @Throws(IOException::class)
        fun copy(
            videoPath: String,
            output: File,
        ): Boolean {
            val format = videoFormat(videoPath) ?: return false
            val boxes = format.projectionData?.let { Mp4Projection.boxes(it, format.stereoMode) } ?: return false
            var added = false
            Mp4MoovFile.edit(output) { moov, headerSize ->
                Mp4Projection.addToVideoTrack(moov, headerSize, boxes)?.also { added = true }
            }
            return added
        }

        private fun videoFormat(path: String): Format? =
            MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(File(path)))).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                (0 until groups.length).map { groups[it].getFormat(0) }.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }
            }

        private companion object {
            const val READ_TIMEOUT_SECONDS = 10L
        }
    }
