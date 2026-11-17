package io.github.aedev.flow.player.stream.model

import java.util.Locale

/**
 * One playable googlevideo format. Plain classes, not data classes: two streams are the same only
 * when they are the same object, which is what every quality, failure and cast lookup keys on.
 *
 * The size, bitrate, codec and byte-range fields are copied from [itagItem] when the stream is
 * made, so a later change to it does not reach the stream.
 */
sealed class Stream(
    /** The itag, as a string. */
    val id: String,
    /** The playable URL. */
    val content: String,
    val format: MediaFormat?,
) {
    abstract val itagItem: ItagInfo?

    /** The itag, or -1 when the stream has no [itagItem]. */
    val itag: Int get() = itagItem?.id ?: -1

    val url: String? get() = content
}

class VideoStream(
    id: String,
    content: String,
    format: MediaFormat?,
    override val itagItem: ItagInfo?,
    /** YouTube's quality label, e.g. "1080p60 HDR". */
    val resolution: String,
    val isVideoOnly: Boolean,
) : Stream(id, content, format) {
    val bitrate: Int = itagItem?.bitrate ?: 0
    val initStart: Int = itagItem?.initStart ?: 0
    val initEnd: Int = itagItem?.initEnd ?: 0
    val indexStart: Int = itagItem?.indexStart ?: 0
    val indexEnd: Int = itagItem?.indexEnd ?: 0
    val width: Int = itagItem?.width ?: 0
    val height: Int = itagItem?.height ?: 0
    val fps: Int = itagItem?.fps ?: 0
    val quality: String? = itagItem?.quality
    val codec: String? = itagItem?.codec
}

class AudioStream(
    id: String,
    content: String,
    format: MediaFormat?,
    override val itagItem: ItagInfo?,
    val averageBitrate: Int,
    val audioTrackId: String? = null,
    val audioTrackName: String? = null,
    val audioLocale: Locale? = null,
    val audioTrackType: AudioTrackType? = null,
) : Stream(id, content, format) {
    val bitrate: Int = itagItem?.bitrate ?: 0
    val initStart: Int = itagItem?.initStart ?: 0
    val initEnd: Int = itagItem?.initEnd ?: 0
    val indexStart: Int = itagItem?.indexStart ?: 0
    val indexEnd: Int = itagItem?.indexEnd ?: 0
    val quality: String? = itagItem?.quality
    val codec: String? = itagItem?.codec
}
