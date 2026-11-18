package io.github.aedev.flow.player.stream

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifest
import org.w3c.dom.Element
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One representation of a DASH manifest, reduced to the attributes the player acts on. The same
 * shape is read from a manifest string and from Media3's in-memory manifest model, so a builder
 * that moves from one to the other can be held to the values the old one produced.
 */
internal data class RepresentationSnapshot(
    val trackType: Int,
    val id: String?,
    val containerMime: String?,
    val codecs: String?,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val sampleRate: Int,
    val channels: Int,
    val baseUrl: String,
    val initStart: Long,
    val initLength: Long,
    val indexStart: Long?,
    val indexLength: Long?,
)

internal data class ManifestSnapshot(
    val dynamic: Boolean,
    val durationMs: Long,
    val minBufferTimeMs: Long,
    val periods: Int,
    val representations: List<RepresentationSnapshot>,
) {
    companion object
}

private const val NO_VALUE = -1

/**
 * Reads a manifest string. Media3's own DashManifestParser cannot run on the plain JVM (it needs
 * android.text.TextUtils and android.util.Pair), so the XML is read with the JDK's parser and each
 * attribute mapped the way the Media3 parser maps it.
 */
internal fun ManifestSnapshot.Companion.fromXml(xml: String): ManifestSnapshot {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    val mpd = doc.documentElement
    val periods = mpd.children("Period")
    val representations =
        periods.flatMap { period ->
            period.children("AdaptationSet").flatMap { set ->
                set.children("Representation").map { rep ->
                    val mime = rep.attr("mimeType") ?: set.attr("mimeType")
                    val segmentBase = rep.children("SegmentBase").single()
                    val init =
                        segmentBase
                            .children("Initialization")
                            .single()
                            .attr("range")!!
                            .byteRange()
                    val index = segmentBase.attr("indexRange")?.byteRange()
                    val channels =
                        (rep.children("AudioChannelConfiguration") + set.children("AudioChannelConfiguration"))
                            .firstOrNull()
                            ?.attr("value")
                            ?.toInt()
                    RepresentationSnapshot(
                        trackType =
                            when {
                                mime?.startsWith("video/") == true -> C.TRACK_TYPE_VIDEO
                                mime?.startsWith("audio/") == true -> C.TRACK_TYPE_AUDIO
                                else -> C.TRACK_TYPE_UNKNOWN
                            },
                        id = rep.attr("id"),
                        containerMime = mime,
                        codecs = rep.attr("codecs") ?: set.attr("codecs"),
                        bitrate = rep.attr("bandwidth")?.toInt() ?: NO_VALUE,
                        width = rep.attr("width")?.toInt() ?: NO_VALUE,
                        height = rep.attr("height")?.toInt() ?: NO_VALUE,
                        frameRate = rep.attr("frameRate")?.frameRate() ?: NO_VALUE.toFloat(),
                        sampleRate = rep.attr("audioSamplingRate")?.toInt() ?: NO_VALUE,
                        channels = channels ?: NO_VALUE,
                        baseUrl =
                            rep
                                .children("BaseURL")
                                .single()
                                .textContent
                                .trim(),
                        initStart = init.first,
                        initLength = init.second,
                        indexStart = index?.first,
                        indexLength = index?.second,
                    )
                }
            }
        }
    return ManifestSnapshot(
        dynamic = mpd.attr("type") == "dynamic",
        durationMs = mpd.attr("mediaPresentationDuration")?.let { Duration.parse(it).toMillis() } ?: C.TIME_UNSET,
        minBufferTimeMs = mpd.attr("minBufferTime")?.let { Duration.parse(it).toMillis() } ?: C.TIME_UNSET,
        periods = periods.size,
        representations = representations,
    )
}

/** Reads Media3's in-memory manifest into the same shape as [fromXml]. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun DashManifest.snapshot(): ManifestSnapshot =
    ManifestSnapshot(
        dynamic = dynamic,
        durationMs = durationMs,
        minBufferTimeMs = minBufferTimeMs,
        periods = periodCount,
        representations =
            (0 until periodCount).flatMap { index ->
                getPeriod(index).adaptationSets.flatMap { set ->
                    set.representations.map { rep ->
                        val format = rep.format
                        val init = rep.initializationUri!!
                        val indexUri = rep.indexUri
                        RepresentationSnapshot(
                            trackType = set.type,
                            id = format.id,
                            containerMime = format.containerMimeType,
                            codecs = format.codecs,
                            bitrate = format.peakBitrate,
                            width = format.width,
                            height = format.height,
                            frameRate = format.frameRate,
                            sampleRate = format.sampleRate,
                            channels = format.channelCount,
                            baseUrl = rep.baseUrls.first().url,
                            initStart = init.start,
                            initLength = init.length,
                            indexStart = indexUri?.start,
                            indexLength = indexUri?.length,
                        )
                    }
                }
            },
    )

private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

private fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

/** "0-740" as start 0, length 741, the way Media3 builds a RangedUri from it. */
private fun String.byteRange(): Pair<Long, Long> {
    val (start, end) = split('-').map(String::toLong)
    return start to (end - start + 1)
}

private fun String.frameRate(): Float {
    val parts = split('/')
    return if (parts.size == 2) parts[0].toFloat() / parts[1].toFloat() else toFloat()
}
