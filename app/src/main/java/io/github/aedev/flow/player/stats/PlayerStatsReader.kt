package io.github.aedev.flow.player.stats

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** The numbers the player manager keeps to itself, which the player object does not report. */
internal data class PlayerStatsReadings(
    val bandwidthBitsPerSecond: Long,
    val decoderName: String?,
    val gainDb: Float?,
)

@UnstableApi
internal object PlayerStatsReader {
    private val device: String by lazy { "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}" }

    fun read(
        player: ExoPlayer,
        readings: PlayerStatsReadings,
        videoId: String,
        kind: String,
        client: String?,
    ): PlayerStatsSnapshot {
        val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
        val surface = player.surfaceSize
        return PlayerStatsSnapshot(
            device = device,
            videoId = videoId,
            kind = kind,
            client = client,
            video =
                player.videoFormat?.let {
                    PlayerStatsSnapshot.VideoStats(it.id, codecOf(it), it.width, it.height, it.frameRate)
                },
            decoder = readings.decoderName,
            audio =
                player.audioFormat?.let {
                    PlayerStatsSnapshot.AudioStats(it.id, codecOf(it), it.bitrate, it.sampleRate)
                },
            volume = player.volume,
            gainDb = readings.gainDb,
            bandwidthBitsPerSecond = readings.bandwidthBitsPerSecond,
            bufferedMs = player.totalBufferedDuration,
            viewportWidth = surface.width,
            viewportHeight = surface.height,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
        )
    }

    private fun codecOf(format: Format): String? = format.codecs ?: format.sampleMimeType
}
