package io.github.aedev.flow.player.live

import androidx.media3.common.MimeTypes
import java.time.Duration
import java.time.Instant

/** One live format the segment source can play. */
data class LiveSegmentFormat(
    val itag: Int,
    val containerMimeType: String,
    val codecs: String,
    val bitrate: Int,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
) {
    val isAudio: Boolean
        get() = MimeTypes.isAudio(containerMimeType)
}

/**
 * Writes the dynamic DASH manifest of a live segment session. Media3 asks for it again every
 * segment; it is a couple of kilobytes written from the clock, against the megabyte HLS playlist
 * each refresh downloads and parses.
 *
 * Every representation is a segment template over the session's segment URIs, with no
 * initialization segment: each live segment carries its own `moov`, which Media3's fragmented MP4
 * extractor reads in place. Video codecs get an adaptation set each, so Media3 adapts within one.
 */
internal object LiveSegmentManifest {
    private const val PERIOD_ID = "live"
    private const val TIMESCALE_US = 1_000_000
    private const val UTC_DIRECT_SCHEME = "urn:mpeg:dash:utc:direct:2014"
    private const val CHANNEL_SCHEME = "urn:mpeg:dash:23003:3:audio_channel_configuration:2011"

    fun build(
        clock: LiveSegmentClock,
        formats: List<LiveSegmentFormat>,
        segmentUri: (itag: Int) -> String,
        serverNowMs: Long,
        lastSequence: Long? = null,
    ): String {
        val segmentMs = clock.segmentDurationUs / 1_000
        val groups = formats.groupBy { if (it.isAudio) "audio" else MimeTypes.getVideoMediaMimeType(it.codecs) ?: it.codecs }.values
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-live:2011" type="dynamic"""")
            attribute("availabilityStartTime", dateTime(clock.availabilityStartTimeMs))
            attribute("publishTime", dateTime(serverNowMs))
            // An ended stream's manifest gets its length and stops being refreshed: Media3 then plays
            // to the last segment and ends, where a live period would wait for one more.
            if (lastSequence == null) {
                attribute("minimumUpdatePeriod", duration(segmentMs))
            } else {
                attribute("mediaPresentationDuration", duration((lastSequence + 1) * clock.segmentDurationUs / 1_000))
            }
            attribute("timeShiftBufferDepth", duration(clock.timeShiftBufferDepthMs))
            attribute("minBufferTime", duration(segmentMs))
            append("><Period")
            attribute("id", PERIOD_ID)
            attribute("start", "PT0S")
            append(">")
            groups.forEachIndexed { index, group ->
                val audio = group.first().isAudio
                append("<AdaptationSet")
                attribute("id", index.toString())
                attribute("contentType", if (audio) "audio" else "video")
                attribute("mimeType", group.first().containerMimeType)
                append(">")
                group.forEach { format ->
                    append("<Representation")
                    attribute("id", format.itag.toString())
                    attribute("codecs", format.codecs)
                    attribute("bandwidth", format.bitrate.toString())
                    format.width?.let { attribute("width", it.toString()) }
                    format.height?.let { attribute("height", it.toString()) }
                    format.frameRate?.let { attribute("frameRate", it.toString()) }
                    format.sampleRate?.let { attribute("audioSamplingRate", it.toString()) }
                    append(">")
                    format.channels?.let {
                        append("<AudioChannelConfiguration")
                        attribute("schemeIdUri", CHANNEL_SCHEME)
                        attribute("value", it.toString())
                        append("/>")
                    }
                    append("<SegmentTemplate")
                    attribute("timescale", TIMESCALE_US.toString())
                    attribute("presentationTimeOffset", clock.presentationTimeOffsetUs.toString())
                    attribute("startNumber", "0")
                    attribute("duration", clock.segmentDurationUs.toString())
                    attribute("media", segmentUri(format.itag).replace("$", "$$") + "\$Number\$")
                    append("/></Representation>")
                }
                append("</AdaptationSet>")
            }
            append("</Period><UTCTiming")
            attribute("schemeIdUri", UTC_DIRECT_SCHEME)
            attribute("value", dateTime(serverNowMs))
            append("/></MPD>")
        }
    }

    private fun StringBuilder.attribute(
        name: String,
        value: String,
    ) {
        append(' ').append(name).append("=\"")
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun dateTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun duration(ms: Long): String = Duration.ofMillis(ms).toString()
}
