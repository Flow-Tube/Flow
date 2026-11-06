package io.github.aedev.flow.player

import androidx.media3.common.MediaItem
import io.github.aedev.flow.data.music.model.MusicTrack

/**
 * The queue as the player holds it, for playback the app did not start itself: a list picked in the
 * car, or the system resuming a saved queue. Each item carries what the app needs to show it.
 */
internal fun adoptedQueue(items: List<MediaItem>): List<MusicTrack> =
    items
        .filter { it.mediaId.isNotBlank() }
        .map { item ->
            val metadata = item.mediaMetadata
            MusicTrack(
                videoId = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                album = metadata.albumTitle?.toString().orEmpty(),
                thumbnailUrl = metadata.webArtworkUrl.orEmpty(),
                duration = ((metadata.durationMs ?: 0L) / MILLIS_PER_SECOND).toInt(),
            )
        }

private const val MILLIS_PER_SECOND = 1000L
