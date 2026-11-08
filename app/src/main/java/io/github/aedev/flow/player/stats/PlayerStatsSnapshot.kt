package io.github.aedev.flow.player.stats

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.aedev.flow.R
import java.util.Locale

private const val BITS_PER_KBIT = 1_000.0
private const val BITS_PER_MBIT = 1_000_000.0
private const val MS_PER_SECOND = 1_000.0
private const val PERCENT = 100
private const val HZ_PER_KHZ = 1_000.0

/** One moment of what the video player is doing, as "Stats for nerds" shows it. */
@Immutable
internal data class PlayerStatsSnapshot(
    val device: String,
    val videoId: String,
    val kind: String,
    val client: String?,
    val video: VideoStats?,
    val decoder: String?,
    val audio: AudioStats?,
    val volume: Float,
    val gainDb: Float?,
    val bandwidthBitsPerSecond: Long,
    val bufferedMs: Long,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val droppedFrames: Int,
    val renderedFrames: Int,
) {
    @Immutable
    data class VideoStats(
        val id: String?,
        val codec: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float,
    )

    @Immutable
    data class AudioStats(
        val id: String?,
        val codec: String?,
        val bitrate: Int,
        val sampleRate: Int,
    )
}

@Immutable
internal data class PlayerStatLine(
    @param:StringRes val label: Int,
    val value: String,
)

internal object PlayerStatsFormatter {
    private const val UNKNOWN = "-"

    fun lines(snapshot: PlayerStatsSnapshot): List<PlayerStatLine> =
        listOf(
            PlayerStatLine(R.string.stats_device, snapshot.device),
            PlayerStatLine(R.string.stats_video_id, videoId(snapshot)),
            PlayerStatLine(R.string.stats_video, snapshot.video?.let(::video) ?: UNKNOWN),
            PlayerStatLine(R.string.stats_decoder, snapshot.decoder ?: UNKNOWN),
            PlayerStatLine(R.string.stats_audio, snapshot.audio?.let(::audio) ?: UNKNOWN),
            PlayerStatLine(R.string.stats_volume, volume(snapshot)),
            PlayerStatLine(R.string.stats_bandwidth, bandwidth(snapshot.bandwidthBitsPerSecond)),
            PlayerStatLine(R.string.stats_buffered, String.format(Locale.US, "%.1f s", snapshot.bufferedMs / MS_PER_SECOND)),
            PlayerStatLine(R.string.stats_viewport, viewport(snapshot)),
            PlayerStatLine(R.string.stats_dropped_frames, "${snapshot.droppedFrames} / ${snapshot.renderedFrames}"),
        )

    private fun viewport(snapshot: PlayerStatsSnapshot): String =
        if (snapshot.viewportWidth > 0 && snapshot.viewportHeight > 0) {
            "${snapshot.viewportWidth}x${snapshot.viewportHeight}"
        } else {
            UNKNOWN
        }

    private fun videoId(snapshot: PlayerStatsSnapshot): String =
        listOfNotNull(snapshot.videoId, "[${snapshot.kind}]", snapshot.client).joinToString(" ")

    private fun video(stats: PlayerStatsSnapshot.VideoStats): String {
        val frameRate =
            stats.frameRate
                .takeIf { it > 0f }
                ?.let { "@%.0f".format(Locale.US, it) }
                .orEmpty()
        return listOfNotNull(stats.id, stats.codec, "${stats.width}x${stats.height}$frameRate").joinToString(" ")
    }

    private fun audio(stats: PlayerStatsSnapshot.AudioStats): String =
        listOfNotNull(
            stats.id,
            stats.codec,
            stats.bitrate.takeIf { it > 0 }?.let { "%.0f kbps".format(Locale.US, it / BITS_PER_KBIT) },
            stats.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(Locale.US, it / HZ_PER_KHZ) },
        ).joinToString(" ")

    private fun volume(snapshot: PlayerStatsSnapshot): String {
        val percent = "${(snapshot.volume * PERCENT).toInt()}%"
        val gain = snapshot.gainDb ?: return percent
        return "$percent ${"%+.1f dB".format(Locale.US, gain)}"
    }

    private fun bandwidth(bitsPerSecond: Long): String =
        if (bitsPerSecond >= BITS_PER_MBIT) {
            "%.1f Mbps".format(Locale.US, bitsPerSecond / BITS_PER_MBIT)
        } else {
            "%.0f kbps".format(Locale.US, bitsPerSecond / BITS_PER_KBIT)
        }
}
