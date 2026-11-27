package io.github.aedev.flow.player.dlna

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.player.stream.selectAudioVariants

/** What a renderer reported when the phone last asked: its transport state and where it was, in ms. */
internal data class CastStatus(
    val state: String,
    val positionMs: Long?,
    val durationMs: Long?,
)

/** The rules [DlnaMusicDriver] plays a queue on a renderer by, kept apart from the network. */
internal object CastQueuePolicy {
    private const val END_SLACK_MS = 5_000L
    private const val END_CHECK_MARGIN_MS = 1_500L
    private const val MIN_CHECK_MS = 2_000L
    const val WATCHED_CHECK_MS = 1_000L

    /** The song after [index] in a queue of [size], or null when the queue ends there. */
    fun next(
        index: Int,
        size: Int,
        repeat: RepeatMode,
    ): Int? =
        when {
            size <= 0 -> null
            repeat == RepeatMode.ONE -> index
            index + 1 < size -> index + 1
            repeat == RepeatMode.ALL -> 0
            else -> null
        }

    fun isPlaying(state: String): Boolean = state == "PLAYING" || state == "TRANSITIONING"

    /**
     * Whether a stopped renderer finished the song rather than being stopped from its own remote:
     * the last position seen was near the end, or the song had time to end since it last played.
     */
    fun finishedSong(
        status: CastStatus,
        lastPositionMs: Long,
        durationMs: Long,
        expectedEndAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (status.state != "STOPPED" && status.state != "NO_MEDIA_PRESENT") return false
        if (durationMs > 0 && lastPositionMs >= durationMs - END_SLACK_MS) return true
        return expectedEndAtMs != null && nowMs >= expectedEndAtMs - END_SLACK_MS
    }

    /**
     * How long until the renderer is asked again: every second while a screen shows the progress,
     * otherwise once just after the song should end, and not at all while nothing plays.
     */
    fun nextCheckMs(
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        watched: Boolean,
    ): Long? =
        when {
            watched -> WATCHED_CHECK_MS
            !playing || durationMs <= 0 -> null
            else -> (durationMs - positionMs + END_CHECK_MARGIN_MS).coerceAtLeast(MIN_CHECK_MS)
        }

    /**
     * The AAC (m4a) stream renderers nearly all play, in the audio track the player chose; opus
     * in webm often isn't supported. Null when the song has none.
     */
    fun castFormat(
        formats: List<PlayerResponse.StreamingData.Format>,
        playing: PlayerResponse.StreamingData.Format?,
    ): PlayerResponse.StreamingData.Format? {
        val aac = formats.filter { it.mimeType.startsWith("audio/mp4") && !it.url.isNullOrEmpty() }.selectAudioVariants()
        val sameTrack = aac.filter { it.audioTrack?.id == playing?.audioTrack?.id }.ifEmpty { aac }
        return sameTrack.maxByOrNull { it.bitrate }
    }
}
