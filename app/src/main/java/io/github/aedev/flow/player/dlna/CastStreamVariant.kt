package io.github.aedev.flow.player.dlna

import io.github.aedev.flow.player.stream.model.Stream

data class CastStreamVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val mime: String = "video/mp4",
    val codec: String = "avc1.64001F",
    val segmentRanges: CastSegmentRanges? = null,
)

/** Inclusive byte ranges of a progressive stream's init segment and its segment index (sidx). */
data class CastSegmentRanges(
    val init: LongRange,
    val index: LongRange,
)

/** Byte count of an inclusive range; [LongRange.count] would walk every byte. */
internal val LongRange.length: Long get() = last - first + 1

internal fun Stream.castSegmentRanges(): CastSegmentRanges? {
    val itag = itagItem ?: return null
    if (itag.initStart < 0 || itag.initEnd <= itag.initStart) return null
    if (itag.indexStart < 0 || itag.indexEnd <= itag.indexStart) return null
    return CastSegmentRanges(
        init = itag.initStart.toLong()..itag.initEnd.toLong(),
        index = itag.indexStart.toLong()..itag.indexEnd.toLong(),
    )
}
