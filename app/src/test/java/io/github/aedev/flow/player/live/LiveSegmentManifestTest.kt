package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read back with the JDK's XML parser, not Media3's, which leans on android.util and cannot run
 * here. The device run is what proves Media3 plays it.
 */
class LiveSegmentManifestTest {
    private val head = LiveHead(sequence = 2_028_589, mediaTimeMs = 10_094_662_121, serverTimeMs = 1_795_449_845_151)
    private val clock = LiveSegmentClock(head, 5_000, 43_200_000)
    private val formats =
        listOf(
            LiveSegmentFormat(298, "video/mp4", "avc1.4d4020", 4_018_075, width = 1280, height = 720, frameRate = 60),
            LiveSegmentFormat(135, "video/mp4", "avc1.4d401f", 1_350_025, width = 854, height = 480, frameRate = 30),
            LiveSegmentFormat(398, "video/mp4", "av01.0.05M.08", 732_800, width = 1280, height = 720, frameRate = 30),
            LiveSegmentFormat(140, "audio/mp4", "mp4a.40.2", 144_000, channels = 2, sampleRate = 48_000),
        )

    private val root: Element by lazy {
        val xml = LiveSegmentManifest.build(clock, formats, { "flowlive://segment/vid/$it/" }, serverNowMs = 1_795_449_850_000)
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement
    }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    @Test
    fun `a dynamic manifest anchored to the head, with a twelve hour window`() {
        assertThat(root.getAttribute("type")).isEqualTo("dynamic")
        assertThat(root.getAttribute("availabilityStartTime")).isEqualTo("2026-07-29T19:59:39.651Z")
        assertThat(root.getAttribute("timeShiftBufferDepth")).isEqualTo("PT12H")
        assertThat(root.getAttribute("minimumUpdatePeriod")).isEqualTo("PT4.976S")
    }

    @Test
    fun `the clock is taken from the server's time, not fetched`() {
        val timing = root.children("UTCTiming").single()
        assertThat(timing.getAttribute("schemeIdUri")).isEqualTo("urn:mpeg:dash:utc:direct:2014")
        assertThat(timing.getAttribute("value")).isEqualTo("2026-11-23T16:04:10Z")
    }

    @Test
    fun `each video codec gets its own adaptation set, audio another`() {
        val sets = root.children("Period").single().children("AdaptationSet")
        val itagsBySet = sets.map { set -> set.children("Representation").map { it.getAttribute("id") } }
        assertThat(itagsBySet).containsExactly(listOf("298", "135"), listOf("398"), listOf("140")).inOrder()
        assertThat(sets.map { it.getAttribute("contentType") }).containsExactly("video", "video", "audio").inOrder()
    }

    @Test
    fun `segments are fetched by number with no initialization segment`() {
        val representation =
            root
                .children("Period")
                .single()
                .children("AdaptationSet")
                .first()
                .children("Representation")
                .first()
        val template = representation.children("SegmentTemplate").single()
        assertThat(template.getAttribute("media")).isEqualTo("flowlive://segment/vid/298/\$Number\$")
        assertThat(template.hasAttribute("initialization")).isFalse()
        assertThat(template.getAttribute("timescale")).isEqualTo("1000000")
        assertThat(template.getAttribute("duration")).isEqualTo("4976198")
        assertThat(template.getAttribute("startNumber")).isEqualTo("0")
        assertThat(template.getAttribute("presentationTimeOffset")).isEqualTo(clock.presentationTimeOffsetUs.toString())
        assertThat(representation.getAttribute("frameRate")).isEqualTo("60")
    }

    @Test
    fun `audio says its channels and sample rate`() {
        val audio =
            root
                .children("Period")
                .single()
                .children("AdaptationSet")
                .last()
                .children("Representation")
                .single()
        assertThat(audio.getAttribute("audioSamplingRate")).isEqualTo("48000")
        assertThat(audio.children("AudioChannelConfiguration").single().getAttribute("value")).isEqualTo("2")
    }

    @Test
    fun `an ended stream gets its length and is no longer refreshed`() {
        val xml = LiveSegmentManifest.build(clock, formats, { "flowlive://segment/vid/$it/" }, 1_795_449_850_000, lastSequence = 719)
        val ended =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
                .documentElement

        assertThat(ended.hasAttribute("minimumUpdatePeriod")).isFalse()
        assertThat(ended.getAttribute("mediaPresentationDuration")).isEqualTo("PT59M42.862S")
    }
}
