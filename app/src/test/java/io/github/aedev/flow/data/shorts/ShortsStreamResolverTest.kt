package io.github.aedev.flow.data.shorts

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShortsStreamResolverTest {
    private val extractions = AtomicInteger()
    private var now = 1_000_000L
    private var result: VideoExtractionResult? =
        reelExtraction(
            videoFormats =
                listOf(
                    reelVideoFormat(
                        itag = 248,
                        mimeType = "video/webm; codecs=\"vp9\"",
                        width = 1080,
                        height = 1920,
                        qualityLabel = "1080p",
                    ),
                    reelVideoFormat(
                        itag = 398,
                        mimeType = "video/mp4; codecs=\"avc1.4d401f\"",
                        width = 720,
                        height = 1280,
                        qualityLabel = "720p",
                    ),
                    reelVideoFormat(itag = 780, mimeType = "video/webm; codecs=\"vp9\"", width = 608, height = 1080, qualityLabel = "480p"),
                    reelVideoFormat(
                        itag = 788,
                        mimeType = "video/mp4; codecs=\"av01.0.05M.08\"",
                        width = 608,
                        height = 1080,
                        qualityLabel = "480p",
                    ),
                ),
            audioFormats =
                listOf(
                    reelAudioFormat(itag = 140, trackId = "en.4", displayName = "English original", audioIsDefault = true),
                    reelAudioFormat(
                        itag = 251,
                        mimeType = "audio/webm; codecs=\"opus\"",
                        bitrate = 160_000,
                        trackId = "en.4",
                        displayName = "English original",
                        audioIsDefault = true,
                    ),
                    reelAudioFormat(itag = 141, trackId = "fr.3", displayName = "French", audioIsDefault = false),
                ),
            expiresInSeconds = 1_000,
        )

    private val resolver =
        ShortsStreamResolver(
            playerPreferences = mockk<PlayerPreferences> { every { videoCodecPriority } returns flowOf("vp9") },
            extract = {
                extractions.incrementAndGet()
                result
            },
            nowMillis = { now },
        )

    @Test
    fun `resolving carries the reel's identity from the same player response`() =
        runBlocking {
            val streams = resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")

            assertNotNull(streams)
            val details = streams!!.details!!
            assertEquals("A reel", details.title)
            assertEquals("Reel Channel", details.channelName)
            assertEquals("UCreelchannel0000000000", details.channelId)
            assertEquals(1_234_567L, details.viewCount)
            assertEquals(28_000L, details.durationMs)
            assertTrue(streams.videoUrl.contains("itag=398"))
            assertTrue(streams.audioUrl!!.contains("itag=251"))
            assertNotNull(streams.videoDashManifest)
            assertNotNull(streams.audioDashManifest)
        }

    @Test
    fun `a loud reel carries the gain that turns it down to YouTube's level`() =
        runBlocking {
            result =
                reelExtraction(
                    videoFormats = result!!.videoFormats,
                    audioFormats = result!!.audioFormats,
                    playerConfig =
                        PlayerResponse.PlayerConfig(
                            audioConfig = PlayerResponse.PlayerConfig.AudioConfig(loudnessDb = 6.0, perceptualLoudnessDb = null),
                        ),
                )

            val streams = resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")

            assertEquals(-6f, streams!!.loudnessGainDb)
        }

    @Test
    fun `one extraction serves playback, the quality sheet, the audio sheet and the download dialog`() =
        runBlocking {
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            val qualities = resolver.availableQualities("reel1234567")
            val tracks = resolver.availableAudioTracks("reel1234567")
            val (video, audio) = resolver.downloadFormats("reel1234567")

            assertEquals(1, extractions.get())
            assertEquals(listOf(1080, 720, 480, 480), qualities.map { it.heightClass })
            assertEquals(listOf("VP9", "H264", "VP9", "AV1"), qualities.map { it.codecLabel })
            assertEquals(listOf("English original", "French"), tracks.map { it.label })
            assertTrue(tracks.first().isOriginal)
            assertEquals(160_000, tracks.first().bitrate)
            assertEquals(4, video.size)
            assertEquals(3, audio.size)
            assertEquals(28_000L, resolver.durationMs("reel1234567"))
        }

    @Test
    fun `a memory trim lets go of every kept reel response`() =
        runBlocking {
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")

            resolver.trimMemory()
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            resolver.availableQualities("reel1234567")

            assertEquals(2, extractions.get())
        }

    @Test
    fun `streams are trusted for four fifths of their lifetime and re-fetched after`() =
        runBlocking {
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            now += 799_000L
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            assertEquals(1, extractions.get())

            now += 2_000L
            resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = "")
            assertEquals(2, extractions.get())
        }

    @Test
    fun `a failed extraction resolves to nothing and is not cached`() =
        runBlocking {
            result = null
            assertNull(resolver.resolve("reel1234567", targetHeight = 720, preferredAudioLanguage = ""))
            assertTrue(resolver.availableQualities("reel1234567").isEmpty())
            assertEquals(2, extractions.get())
        }
}
