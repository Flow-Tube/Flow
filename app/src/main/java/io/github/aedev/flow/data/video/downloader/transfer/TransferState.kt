package io.github.aedev.flow.data.video.downloader.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The blocks of a download already on disk, saved beside its part files so a resume after a pause,
 * a lost network or the app being killed continues instead of starting over.
 *
 * A block map only fits the stream it was written for, so it is keyed by itag, audio track and exact
 * size: a re-extracted URL for the same itag serves the same bytes, anything else starts from zero.
 */
@Serializable
data class TransferState(
    val streams: List<StreamState>,
) {
    @Serializable
    data class StreamState(
        val role: StreamRole,
        val itag: Int,
        val totalBytes: Long,
        val completedBlocks: List<Int>,
        val partialBlockBytes: Map<Int, Long>,
        val trackId: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(job: TransferJob): TransferState =
            TransferState(
                job.streams.map { stream ->
                    StreamState(
                        role = stream.role,
                        itag = stream.itag,
                        totalBytes = stream.totalBytes,
                        completedBlocks = stream.completedBlocks.sorted(),
                        partialBlockBytes = stream.partialBlockBytes.toMap(),
                        trackId = stream.trackId,
                    )
                },
            )

        fun read(file: File): TransferState? = runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()

        /** Written to a sibling and renamed, so a crash mid-write never leaves half a map behind. */
        fun write(
            file: File,
            state: TransferState,
        ) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer(), state))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    /**
     * Restores the saved blocks onto [streams] whose itag, track, size and part file still match, and
     * reports whether anything was restored. A stream that no longer matches starts from zero.
     */
    fun restoreInto(streams: List<TransferStream>): Boolean {
        var restored = false
        streams.forEach { stream ->
            val saved =
                this.streams.firstOrNull { it.role == stream.role && it.itag == stream.itag && it.trackId == stream.trackId }
                    ?: return@forEach
            if (saved.totalBytes <= 0L || saved.totalBytes != stream.totalBytes) return@forEach
            if (!stream.file.exists() || stream.file.length() != saved.totalBytes) return@forEach
            stream.completedBlocks.addAll(saved.completedBlocks)
            stream.partialBlockBytes.putAll(saved.partialBlockBytes)
            restored = restored || saved.completedBlocks.isNotEmpty() || saved.partialBlockBytes.isNotEmpty()
        }
        return restored
    }
}
