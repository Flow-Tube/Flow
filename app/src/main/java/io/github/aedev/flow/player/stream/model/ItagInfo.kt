package io.github.aedev.flow.player.stream.model

/**
 * Everything known about one googlevideo format: what YouTube's itag table says about the itag,
 * overwritten field by field with what the `/player` response says about this copy of it.
 *
 * A value YouTube did not give keeps its "unknown" default: 0 for sizes, bitrate and byte ranges,
 * -1 for content length, duration, sample rate and channel count, null for the codec and quality.
 */
class ItagInfo(
    val id: Int,
    val itagType: ItagType,
    val mediaFormat: MediaFormat?,
    val resolutionString: String?,
    var fps: Int,
) {
    enum class ItagType {
        /** Muxed: video and audio in one file. */
        VIDEO,
        VIDEO_ONLY,
        AUDIO,
    }

    var bitrate: Int = 0
    var codec: String? = null
    var width: Int = 0
    var height: Int = 0
    var initStart: Int = 0
    var initEnd: Int = 0
    var indexStart: Int = 0
    var indexEnd: Int = 0
    var contentLength: Long = -1L
    var approxDurationMs: Long = -1L
    var lastModified: Long = 0L
    var quality: String? = null
    var sampleRate: Int = -1
    var audioChannels: Int = -1

    companion object {
        /** The itag's entry in YouTube's table, or null for an itag the table does not list. */
        fun forItag(itag: Int): ItagInfo? =
            KnownItags.byItag[itag]?.let { known ->
                ItagInfo(itag, known.type, known.format, known.resolution, known.fps)
            }
    }
}
