package io.github.aedev.flow.player.quality

import io.github.aedev.flow.player.resolver.AdaptiveDashManifest
import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * The qualities Auto hands to Media3 as one source: every describable stream in the anchor's codec
 * and colour depth, one per height. Media3 cannot switch between codecs or between SDR and HDR
 * without a visible decoder change, so those never share a ladder.
 */
internal object AdaptiveLadder {
    private const val MIN_RUNGS = 2

    /** Empty when fewer than two qualities qualify, which leaves Auto on a single stream. */
    fun rungs(
        anchor: VideoStream?,
        streams: List<VideoStream>,
    ): List<VideoStream> {
        anchor ?: return emptyList()
        val codec = VideoCodecUtils.codecKeyFromStream(anchor)
        val deep = isHighBitDepth(anchor)
        return streams
            .filter {
                AdaptiveDashManifest.canDescribe(it) &&
                    VideoCodecUtils.codecKeyFromStream(it) == codec &&
                    isHighBitDepth(it) == deep &&
                    it.format == anchor.format
            }.groupBy(VideoCodecUtils::qualityHeightFromStream)
            .values
            .map { sameHeight -> sameHeight.maxBy { it.bitrate } }
            .sortedBy { it.bitrate }
            .takeIf { it.size >= MIN_RUNGS }
            .orEmpty()
    }

    /**
     * The ladder a preloaded video plays on: none when a quality is pinned ([pinned] set), else one
     * around [anchorFor]. Promotion recomputes it from the same inputs to learn what is playing.
     */
    fun forPreload(
        pinned: VideoStream?,
        streams: List<VideoStream>,
        preferredCodec: String?,
    ): List<VideoStream> = if (pinned != null) emptyList() else rungs(anchorFor(streams, preferredCodec), streams)

    /** The anchor of a ladder built before the video has had any bandwidth to judge by. */
    private fun anchorFor(
        streams: List<VideoStream>,
        preferredCodec: String?,
    ): VideoStream? =
        streams
            .filter(AdaptiveDashManifest::canDescribe)
            .minWithOrNull(
                compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenByDescending(VideoCodecUtils::qualityHeightFromStream),
            )

    /** HDR and 10-bit SDR: VP9 profile 2, AV1 at 10 bits, HEVC Main 10. */
    internal fun isHighBitDepth(stream: VideoStream): Boolean {
        val codecs = (stream.itagItem?.codec ?: "").lowercase()
        val parts = codecs.split('.')
        return when (parts.firstOrNull()) {
            "vp09" -> parts.getOrNull(1) == "02" || parts.getOrNull(1) == "03"
            "vp9" -> parts.getOrNull(1) == "2" || parts.getOrNull(1) == "3"
            "av01" -> parts.getOrNull(3) == "10" || parts.getOrNull(3) == "12"
            "hev1", "hvc1" -> parts.getOrNull(1) == "2"
            else -> false
        }
    }
}
