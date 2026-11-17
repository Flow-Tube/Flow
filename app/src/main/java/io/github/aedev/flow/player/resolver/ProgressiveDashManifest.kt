package io.github.aedev.flow.player.resolver

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A single-representation DASH manifest over one progressive googlevideo URL, with the format's
 * init and sidx byte ranges, so ExoPlayer fetches it in ranged chunks instead of one throttled GET.
 *
 * Written with the same elements and attributes NewPipe's progressive manifest creator wrote, and
 * refused in the same cases, so every player and Shorts path reads exactly the manifest it read
 * before. YouTube's sample rate is left out as it was there: Media3 reads it from the container.
 */
internal object ProgressiveDashManifest {
    /** What a representation needs from one format. A non-positive value means YouTube did not say. */
    data class Input(
        val itag: Int,
        val isAudio: Boolean,
        val containerMimeType: String?,
        val codec: String?,
        val bitrate: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val audioChannels: Int,
        val initStart: Int,
        val initEnd: Int,
        val indexStart: Int,
        val indexEnd: Int,
        val approxDurationMs: Long,
    )

    /** Null when the format cannot be described: no duration, codec, bitrate, size, channels or byte ranges. */
    fun create(
        url: String,
        input: Input,
        durationSecondsFallback: Long,
    ): String? {
        val durationMs =
            when {
                input.approxDurationMs != -1L -> input.approxDurationMs
                durationSecondsFallback > 0 -> durationSecondsFallback * 1000L
                else -> return null
            }
        val mimeType = input.containerMimeType?.takeIf { it.isNotEmpty() } ?: return null
        val codec = input.codec?.takeIf { it.isNotEmpty() } ?: return null
        if (input.itag <= 0 || input.bitrate <= 0) return null
        if (!input.isAudio && input.width <= 0 && input.height <= 0) return null
        if (input.isAudio && input.audioChannels <= 0) return null
        if (input.indexStart < 0 || input.indexEnd < 0 || input.initStart < 0 || input.initEnd < 0) return null

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val mpd =
            document.child(document, "MPD") {
                setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
                setAttribute("xmlns", "urn:mpeg:DASH:schema:MPD:2011")
                setAttribute("xsi:schemaLocation", "urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd")
                setAttribute("minBufferTime", "PT1.500S")
                setAttribute("profiles", "urn:mpeg:dash:profile:full:2011")
                setAttribute("type", "static")
                setAttribute("mediaPresentationDuration", String.format(Locale.ENGLISH, "PT%.3fS", durationMs / 1000.0))
            }
        val period = document.child(mpd, "Period")
        val adaptationSet =
            document.child(period, "AdaptationSet") {
                setAttribute("id", "0")
                setAttribute("mimeType", mimeType)
                setAttribute("subsegmentAlignment", "true")
            }
        document.child(adaptationSet, "Role") {
            setAttribute("schemeIdUri", "urn:mpeg:DASH:role:2011")
            setAttribute("value", "main")
        }
        val representation =
            document.child(adaptationSet, "Representation") {
                setAttribute("id", input.itag.toString())
                setAttribute("codecs", codec)
                setAttribute("startWithSAP", "1")
                setAttribute("maxPlayoutRate", "1")
                setAttribute("bandwidth", input.bitrate.toString())
                if (!input.isAudio) {
                    if (input.width > 0) setAttribute("width", input.width.toString())
                    setAttribute("height", input.height.toString())
                    if (input.fps > 0) setAttribute("frameRate", input.fps.toString())
                }
            }
        if (input.isAudio) {
            document.child(representation, "AudioChannelConfiguration") {
                setAttribute("schemeIdUri", "urn:mpeg:dash:23003:3:audio_channel_configuration:2011")
                setAttribute("value", input.audioChannels.toString())
            }
        }
        document.child(representation, "BaseURL") { textContent = url }
        val segmentBase =
            document.child(
                representation,
                "SegmentBase",
            ) { setAttribute("indexRange", "${input.indexStart}-${input.indexEnd}") }
        document.child(segmentBase, "Initialization") { setAttribute("range", "${input.initStart}-${input.initEnd}") }
        return document.serialize()
    }

    private fun Document.child(
        parent: org.w3c.dom.Node,
        name: String,
        configure: Element.() -> Unit = {},
    ): Element = createElement(name).also { it.configure() }.also { parent.appendChild(it) }

    private fun Document.serialize(): String {
        val factory = TransformerFactory.newInstance()
        val transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.VERSION, "1.0")
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no")
        return StringWriter().also { transformer.transform(DOMSource(this), StreamResult(it)) }.toString()
    }
}
