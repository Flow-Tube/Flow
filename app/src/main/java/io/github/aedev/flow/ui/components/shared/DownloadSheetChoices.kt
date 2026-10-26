package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Immutable
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format

/** A codec, in SDR or HDR, that one resolution of the download sheet offers. */
@Immutable
internal data class DownloadFormatOption(
    val codecKey: String,
    val hdr: Boolean,
)

/**
 * One resolution of the download sheet. [formats] keep the ladder's order, so the first format of an
 * option is its highest frame rate.
 */
@Immutable
internal data class DownloadQuality(
    val height: Int,
    val fps: Int,
    val formats: List<Format>,
) {
    val options: List<DownloadFormatOption> =
        formats.map { DownloadFormatOption(DownloadStreamPolicy.videoCodecKey(it), DownloadStreamPolicy.isHdr(it)) }.distinct()

    fun format(option: DownloadFormatOption): Format? =
        formats.firstOrNull {
            DownloadStreamPolicy.videoCodecKey(it) == option.codecKey && DownloadStreamPolicy.isHdr(it) == option.hdr
        }
}

/** What the download sheet offers and opens on, kept apart from the sheet so it can be tested. */
internal object DownloadSheetChoices {
    /** [ladder] from [DownloadStreamPolicy.buildDownloadVideoFormats], one entry per resolution, highest first. */
    fun qualities(ladder: List<Format>): List<DownloadQuality> =
        ladder
            .groupBy(DownloadStreamPolicy::videoHeight)
            .map { (height, formats) -> DownloadQuality(height, formats.maxOf { it.fps ?: 0 }, formats) }
            .sortedByDescending { it.height }

    /**
     * The resolution the sheet opens on: the Download quality, else the nearest one below it, else the
     * lowest. A [preferredHeight] of 0 or less means no preference, and takes the highest.
     */
    fun preselect(
        qualities: List<DownloadQuality>,
        preferredHeight: Int,
    ): DownloadQuality? {
        if (preferredHeight <= 0) return qualities.maxByOrNull { it.height }
        return qualities.filter { it.height <= preferredHeight }.maxByOrNull { it.height }
            ?: qualities.minByOrNull { it.height }
    }

    /** The option [quality] downloads in: [current] when it has it, else [preferredCodec] in SDR, else its first. */
    fun option(
        quality: DownloadQuality,
        current: DownloadFormatOption?,
        preferredCodec: String?,
    ): DownloadFormatOption? =
        quality.options.firstOrNull { it == current }
            ?: quality.options.firstOrNull { it.codecKey == preferredCodec && !it.hdr }
            ?: quality.options.firstOrNull()

    /** The audio tiles shown before All languages: the original track and the one in the viewer's language. */
    fun primaryAudio(
        audio: List<Format>,
        preferredTrackId: String?,
    ): List<Format> =
        audio
            .filter { it.isOriginal || (preferredTrackId != null && it.audioTrack?.id == preferredTrackId) }
            .ifEmpty { audio }
}
