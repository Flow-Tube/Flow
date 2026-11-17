package io.github.aedev.flow.player.resolver

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.quality.LadderTestStreams.h264
import io.github.aedev.flow.player.quality.LadderTestStreams.vp9
import io.github.aedev.flow.player.stream.model.VideoStream
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A ladder rung must be the stream exactly as the single-quality path already plays it, so each
 * one is checked against the manifest [ManifestGenerator] writes for that stream on its own.
 *
 * That manifest is read with the JDK's XML parser, not Media3's: Media3's parser leans on
 * android.text and android.util, and Robolectric cannot load its natives on the Linux CI runner.
 */
class AdaptiveDashManifestTest {
    private val rungs = listOf(h264(360, 700_000), h264(720, 2_500_000), h264(1080, 4_500_000))

    private class SingleStreamManifest(
        val root: Element,
    ) {
        private fun first(tag: String) = root.getElementsByTagName(tag).item(0) as Element

        val durationMs get() = Duration.parse(root.getAttribute("mediaPresentationDuration")).toMillis()
        val mimeType get() = first("AdaptationSet").getAttribute("mimeType")
        val representation get() = first("Representation")
        val baseUrl get() = first("BaseURL").textContent
        val initializationRange get() = first("Initialization").getAttribute("range")
        val indexRange get() = first("SegmentBase").getAttribute("indexRange")
    }

    private fun singleStreamManifest(stream: VideoStream): SingleStreamManifest {
        val xml = ManifestGenerator.generateProgressiveManifest(stream, stream.itagItem!!, 212)!!
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
        return SingleStreamManifest(document.documentElement)
    }

    private fun DashManifest.videoRepresentations(): List<Representation> =
        getPeriod(0).adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { it.representations }

    private fun RangedUri.range() = "$start-${start + length - 1}"

    @Test
    fun `every rung is described exactly as the single-stream manifest describes it`() {
        val ladder = AdaptiveDashManifest.build(rungs, 212)!!

        val built = ladder.videoRepresentations()
        assertThat(built).hasSize(rungs.size)
        rungs.zip(built).forEach { (stream, rung) ->
            val single = singleStreamManifest(stream)
            val described = single.representation
            assertThat(rung.baseUrls.single().url).isEqualTo(single.baseUrl)
            assertThat(rung.format.id).isEqualTo(described.getAttribute("id"))
            assertThat(rung.format.containerMimeType).isEqualTo(single.mimeType)
            assertThat(rung.format.codecs).isEqualTo(described.getAttribute("codecs"))
            assertThat(rung.format.sampleMimeType)
                .isEqualTo(MimeTypes.getVideoMediaMimeType(described.getAttribute("codecs")))
            assertThat(rung.format.bitrate).isEqualTo(described.getAttribute("bandwidth").toInt())
            assertThat(rung.format.width).isEqualTo(described.getAttribute("width").toInt())
            assertThat(rung.format.height).isEqualTo(described.getAttribute("height").toInt())
            assertThat(rung.format.frameRate).isEqualTo(described.getAttribute("frameRate").toFloat())
            assertThat(rung.initializationUri!!.range()).isEqualTo(single.initializationRange)
            assertThat(rung.indexUri!!.range()).isEqualTo(single.indexRange)
        }
        assertThat(ladder.durationMs).isEqualTo(singleStreamManifest(rungs[0]).durationMs)
        assertThat(ladder.dynamic).isFalse()
    }

    @Test
    fun `all rungs share one video adaptation set`() {
        val ladder = AdaptiveDashManifest.build(rungs, 212)!!

        assertThat(ladder.getPeriod(0).adaptationSets).hasSize(1)
    }

    @Test
    fun `a WebM rung keeps its container`() {
        val ladder = AdaptiveDashManifest.build(listOf(vp9(360, 600_000), vp9(720, 2_000_000)), 212)!!

        val single = singleStreamManifest(vp9(360, 600_000))
        assertThat(
            ladder
                .videoRepresentations()
                .first()
                .format.containerMimeType,
        ).isEqualTo(single.mimeType)
    }

    @Test
    fun `fewer than two describable rungs make no ladder`() {
        assertThat(AdaptiveDashManifest.build(rungs.take(1), 212)).isNull()
        assertThat(AdaptiveDashManifest.build(listOf(rungs[0], h264(720, 2_500_000, ranged = false)), 212)).isNull()
    }
}
