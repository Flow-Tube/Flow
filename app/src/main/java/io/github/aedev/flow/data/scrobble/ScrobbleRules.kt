package io.github.aedev.flow.data.scrobble

import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack

/**
 * The listen rules every scrobbling service shares: a track longer than 30 seconds counts once
 * it has played for half its length or four minutes, whichever comes first.
 */
object ScrobbleRules {
    private const val MIN_DURATION_MS = 30_000L
    private const val MAX_THRESHOLD_MS = 4 * 60_000L
    private const val TOPIC_SUFFIX = " - Topic"

    /** How long a track must play before it counts; null when it is too short to ever count. */
    fun thresholdMs(durationMs: Long): Long? = if (durationMs > MIN_DURATION_MS) minOf(durationMs / 2, MAX_THRESHOLD_MS) else null

    fun counts(
        durationMs: Long,
        playedMs: Long,
    ): Boolean = thresholdMs(durationMs)?.let { playedMs >= it } ?: false

    /** Null when the track has no artist or title to send. */
    fun entryFor(
        track: MusicTrack,
        durationMs: Long,
        startedAtMs: Long,
    ): ScrobbleEntry? {
        val (artist, title) = artistAndTitle(track) ?: return null
        return ScrobbleEntry(
            artist = artist,
            title = title,
            album =
                track.album
                    .trim()
                    .takeUnless { it.equals(UNKNOWN_ALBUM, ignoreCase = true) }
                    .orEmpty(),
            durationSec = (durationMs / 1000).toInt(),
            timestampSec = startedAtMs / 1000,
            fromYouTube = !LocalMediaIds.isLocal(track.videoId),
            videoId = track.videoId,
            thumbnailUrl = track.webArtwork(),
        )
    }

    fun loveFor(
        track: MusicTrack,
        loved: Boolean,
    ): LoveEntry? = artistAndTitle(track)?.let { (artist, title) -> LoveEntry(artist, title, loved, track.videoId) }

    /** The first credited artist: joining every name makes Last.fm invent a new artist. */
    private fun artistAndTitle(track: MusicTrack): Pair<String, String>? {
        val artist =
            (track.artists.firstOrNull()?.name ?: track.artist)
                .removeSuffix(TOPIC_SUFFIX)
                .trim()
        val title = track.title.trim()
        return if (artist.isEmpty() || title.isEmpty()) null else artist to title
    }

    private fun MusicTrack.webArtwork(): String =
        if (LocalMediaIds.isLocal(videoId)) "" else highResThumbnailUrl.takeIf { it.startsWith("https://") }.orEmpty()

    private const val UNKNOWN_ALBUM = "Unknown Album"
}
