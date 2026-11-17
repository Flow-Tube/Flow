package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.error.RecoveryStreams
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * What a DLNA renderer is offered for a video: the variants per cast mode, the audio beside them,
 * and the muxed fallback, in the order the ladder tries them. Registration is refused on purpose,
 * so the ladder walks every mode without a renderer and without remembering a failure.
 */
class CastStreamInfoTest {
    private data class Offer(
        val variants: List<CastStreamVariant>,
        val audioUrl: String,
        val audioMime: String,
        val audioBitrate: Int,
        val audioCodec: String,
        val audioSegmentRanges: CastSegmentRanges?,
        val durationSeconds: Long,
    )

    private val proxy = StreamProxyServer.getInstance()
    private val hlsOffers = CopyOnWriteArrayList<Offer>()
    private val muxedOffers = CopyOnWriteArrayList<Pair<String, String>>()
    private val device =
        DlnaDevice(
            friendlyName = "Test TV",
            location = "http://192.0.2.10:1400/description.xml",
            usn = "uuid:test-${System.nanoTime()}::urn:schemas-upnp-org:device:MediaRenderer:1",
        )

    @Before
    fun setUp() {
        mockkObject(proxy)
        every { proxy.registerHlsCast(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            hlsOffers +=
                Offer(
                    variants = secondArg(),
                    audioUrl = thirdArg(),
                    audioMime = arg(3),
                    audioBitrate = arg(4),
                    audioCodec = arg(5),
                    audioSegmentRanges = arg(6),
                    durationSeconds = arg(7),
                )
            throw IllegalStateException("not registering in a test")
        }
        every { proxy.registerStream(any(), any(), any(), any()) } answers {
            muxedOffers += firstArg<String>() to thirdArg<String>()
            throw IllegalStateException("not registering in a test")
        }
    }

    @After
    fun tearDown() {
        DlnaCastManager.stop()
        unmockkObject(proxy)
    }

    private fun awaitOffers(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (hlsOffers.size + muxedOffers.size < count && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(100)
    }

    private val ranges = CastSegmentRanges(init = 0L..740L, index = 741L..1560L)

    @Test
    fun `a 4K AV1 video offers segmented AV1, segmented H264, whole-file H264, then the muxed stream`() {
        val av1 = RecoveryStreams.av1(2160, itag = 401)
        val h264 = RecoveryStreams.h264(1080, itag = 137)
        val h264Low = RecoveryStreams.h264(720, itag = 136)
        val vp9 = RecoveryStreams.vp9(1440, itag = 271)
        val aac = RecoveryStreams.aac()
        val opus = RecoveryStreams.opus()
        val muxed = RecoveryStreams.muxed(360)

        val started =
            DlnaCastManager.castStreamInfo(
                device = device,
                title = "Title",
                currentPlayerUrl = null,
                videoOnlyStreams = listOf(h264Low, vp9, av1, h264),
                audioStreams = listOf(opus, aac),
                muxedStreams = listOf(muxed),
                durationSeconds = 212,
            )
        awaitOffers(4)

        assertThat(started).isTrue()
        val av1Variant =
            CastStreamVariant(
                url = RecoveryStreams.url(401),
                width = 3840,
                height = 2160,
                bitrate = 2_000_000,
                mime = "video/mp4",
                codec = "av01.0.12m.08",
                segmentRanges = ranges,
            )
        val h264Variant =
            CastStreamVariant(
                url = RecoveryStreams.url(137),
                width = 1920,
                height = 1080,
                bitrate = 2_000_000,
                mime = "video/mp4",
                codec = "avc1.640028",
                segmentRanges = ranges,
            )
        val audio = { segmented: Boolean, variants: List<CastStreamVariant> ->
            Offer(variants, RecoveryStreams.url(140), "audio/mp4", 129_000, "mp4a.40.2", ranges.takeIf { segmented }, 212)
        }
        assertThat(hlsOffers)
            .containsExactly(
                audio(true, listOf(av1Variant)),
                audio(true, listOf(h264Variant)),
                audio(false, listOf(h264Variant.copy(segmentRanges = null))),
            ).inOrder()
        assertThat(muxedOffers).containsExactly(RecoveryStreams.url(18) to "video/mp4")
    }

    @Test
    fun `AV1 no higher than the best H264 is never offered, and unranged streams skip the segmented mode`() {
        val started =
            DlnaCastManager.castStreamInfo(
                device = device,
                title = "Title",
                currentPlayerUrl = "https://example.invalid/current.mp4",
                videoOnlyStreams = listOf(RecoveryStreams.av1(1080), RecoveryStreams.h264(1080, ranged = false)),
                audioStreams = listOf(RecoveryStreams.aac(ranged = false)),
                muxedStreams = emptyList(),
                durationSeconds = 60,
            )
        awaitOffers(2)

        assertThat(started).isTrue()
        assertThat(hlsOffers.map { offer -> offer.variants.map { it.codec to it.segmentRanges } })
            .containsExactly(listOf("avc1.640028" to null))
        assertThat(hlsOffers.single().audioSegmentRanges).isNull()
        assertThat(muxedOffers).containsExactly("https://example.invalid/current.mp4" to "video/mp4")
    }

    @Test
    fun `without an mp4 audio stream only the muxed stream is offered`() {
        val started =
            DlnaCastManager.castStreamInfo(
                device = device,
                title = "Title",
                currentPlayerUrl = null,
                videoOnlyStreams = listOf(RecoveryStreams.h264(1080)),
                audioStreams = listOf(RecoveryStreams.opus()),
                muxedStreams = listOf(RecoveryStreams.muxed(360)),
                durationSeconds = 60,
            )
        awaitOffers(1)

        assertThat(started).isTrue()
        assertThat(hlsOffers).isEmpty()
        assertThat(muxedOffers).containsExactly(RecoveryStreams.url(18) to "video/mp4")
    }

    @Test
    fun `a video with nothing a renderer can fetch is not cast`() {
        val started =
            DlnaCastManager.castStreamInfo(
                device = device,
                title = "Title",
                currentPlayerUrl = "local://download/abc",
                videoOnlyStreams = listOf(RecoveryStreams.vp9(1080)),
                audioStreams = listOf(RecoveryStreams.aac()),
                muxedStreams = emptyList(),
                durationSeconds = 60,
            )

        assertThat(started).isFalse()
        assertThat(hlsOffers).isEmpty()
        assertThat(muxedOffers).isEmpty()
    }
}
