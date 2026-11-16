package io.github.aedev.flow.player.analytics

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.aedev.flow.player.error.PlayerDiagnostics
import kotlin.math.roundToInt

@UnstableApi
class PlaybackAnalyticsLogger(
    private val tag: String,
    private val videoIdProvider: () -> String? = { null },
) : AnalyticsListener {
    var activeDecoderName: String? = null
        private set
    private var activeFormatSummary: String = "unknown"
    private var droppedFramesSinceFormatChange: Int = 0

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        droppedFramesSinceFormatChange = 0
        activeFormatSummary = format.summary()
        PlayerDiagnostics.logInfo(
            tag,
            "Video format: videoId=${videoIdProvider() ?: "unknown"} $activeFormatSummary",
        )
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        activeDecoderName = decoderName
        val isSoftware =
            decoderName.startsWith("c2.android.") ||
                decoderName.startsWith("OMX.google.")
        val message =
            "Video decoder: $decoderName (${if (isSoftware) "SOFTWARE" else "hardware"}) " +
                "init=${initializationDurationMs}ms format=$activeFormatSummary"
        if (isSoftware) {
            PlayerDiagnostics.logWarning(tag, "⚠ SOFTWARE video decode — high battery cost. $message")
        } else {
            PlayerDiagnostics.logInfo(tag, message)
        }
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        droppedFramesSinceFormatChange += droppedFrames
        val severity =
            if (droppedFrames >= 5 || droppedFramesSinceFormatChange >= 20) {
                "Dropped video frames"
            } else {
                "Minor video frame drops"
            }
        PlayerDiagnostics.logWarning(
            tag,
            "$severity: +$droppedFrames in ${elapsedMs}ms, total=$droppedFramesSinceFormatChange, " +
                "decoder=${activeDecoderName ?: "unknown"}, format=$activeFormatSummary, " +
                "videoId=${videoIdProvider() ?: "unknown"}",
        )
    }

    private fun Format.summary(): String {
        val frameRateText = if (frameRate > 0f) " ${frameRate.roundToInt()}fps" else ""
        val bitrateText = if (bitrate > 0) " ${bitrate / 1000}kbps" else ""
        val codecText = codecs?.takeIf { it.isNotBlank() }?.let { " codecs=$it" }.orEmpty()
        return "${sampleMimeType ?: "unknown"} ${width}x$height$frameRateText$bitrateText$codecText".trim()
    }
}
