package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.model.Video
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One batch of videos that continues a queue, and whether another follows it. */
data class QueuePage(
    val videos: List<Video>,
    val hasMore: Boolean,
)

/**
 * Where a queue's later videos come from. Each [next] returns the batch after the last one it
 * returned; a failed read returns null and the same batch is asked for again next time.
 */
interface QueuePageSource {
    /** Where this source has got to, so a restored queue can carry on from the same place. */
    val spec: QueueSourceSpec

    suspend fun next(): QueuePage?
}

/** A [QueuePageSource] as stored with a saved queue. */
@Serializable
sealed class QueueSourceSpec {
    /** The playlist's own pages, from the browse continuation [token]. */
    @Serializable
    @SerialName("playlist")
    data class PlaylistPages(
        val playlistId: String,
        val token: String,
    ) : QueueSourceSpec()

    /** The watch page's playlist panel, from its continuation [token]. */
    @Serializable
    @SerialName("watch")
    data class WatchPanel(
        val playlistId: String,
        val token: String,
    ) : QueueSourceSpec()
}
