package io.github.aedev.flow.player.media

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.cache.PlayerCacheManager
import io.github.aedev.flow.player.resolver.Media3JvmShims
import io.github.aedev.flow.player.resolver.SourceShape
import io.github.aedev.flow.player.resolver.shape
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opus
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opusFormat
import io.github.aedev.flow.player.stream.TestAudioStream
import io.github.aedev.flow.player.stream.TestVideoStream
import io.github.aedev.flow.player.stream.testUrl
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What [MediaLoader] hands the player for a set of streams: which streams reach the resolver (the
 * one picked, Auto's ladder, or YouTube's manifest over every stream), whether the load starts and
 * where, and the local-file and failure paths beside them.
 */
@UnstableApi
class MediaLoaderTest {
    private val h360 = h264(360, 700_000)
    private val h720 = h264(720, 2_500_000)
    private val h1080 = h264(1080, 4_500_000)
    private val audio = opus()
    private val duration = 212L

    private val state = MutableStateFlow(EnhancedPlayerState())
    private val context = mockk<Context>(relaxed = true)
    private val source = slot<MediaSource>()
    private val player =
        mockk<ExoPlayer>(relaxed = true) {
            every { setMediaSource(capture(source)) } returns Unit
        }

    @Before
    fun shims() = Media3JvmShims.install()

    @After
    fun unshim() = Media3JvmShims.uninstall()

    private fun loader(cacheManager: PlayerCacheManager? = null) = MediaLoader(context, state, cacheManager, null)

    private fun MediaLoader.load(
        videoStream: TestVideoStream? = h720,
        audioStream: TestAudioStream? = audio,
        available: List<TestVideoStream> = listOf(h1080, h720, h360),
        current: TestVideoStream? = h720,
        dashUrl: String? = null,
        hlsUrl: String? = null,
        isLive: Boolean = false,
        durationSeconds: Long = duration,
        currentDurationSeconds: Long = 0L,
        preservePosition: Long? = null,
        localFilePath: String? = null,
        audioOnly: Boolean = false,
        playWhenReady: Boolean = true,
        ladder: List<TestVideoStream> = emptyList(),
        playerArg: ExoPlayer? = player,
    ) = loadMedia(
        player = playerArg,
        context = context,
        videoStream = videoStream,
        audioStream = audioStream,
        availableVideoStreams = available,
        currentVideoStream = current,
        dashManifestUrl = dashUrl,
        hlsUrl = hlsUrl,
        isLiveStream = isLive,
        durationSeconds = durationSeconds,
        currentDurationSeconds = currentDurationSeconds,
        preservePosition = preservePosition,
        localFilePath = localFilePath,
        audioOnly = audioOnly,
        playWhenReady = playWhenReady,
        mediaId = "vid_1",
        adaptiveLadder = ladder,
    )

    private val loaded: SourceShape get() = source.captured.shape()!!

    private fun SourceShape.videoUrls() =
        children
            .first()
            .sideloadedManifest!!
            .representations
            .map { it.baseUrl }

    @Test
    fun `the picked stream loads with its audio, prepares and plays`() {
        assertThat(loader().load()).isTrue()

        assertThat(loaded.kind).isEqualTo("merging")
        assertThat(loaded.videoUrls()).containsExactly(h720.testUrl)
        assertThat(loaded.children[1].uri).isEqualTo(audio.testUrl)
        verify { player.prepare() }
        verify { player.playWhenReady = true }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
        assertThat(state.value.isPrepared).isTrue()
    }

    @Test
    fun `a reload resumes at the preserved position and can stay paused`() {
        loader().load(preservePosition = 61_000L, playWhenReady = false)

        verify { player.seekTo(61_000L) }
        verify { player.playWhenReady = false }
    }

    @Test
    fun `Auto's ladder replaces the single stream when it has two or more rungs`() {
        val loader = loader()

        loader.load(videoStream = null, ladder = listOf(h360, h720, h1080))

        assertThat(loaded.videoUrls()).containsExactly(h360.testUrl, h720.testUrl, h1080.testUrl).inOrder()
        assertThat(loader.lastSourceWasAdaptiveLadder).isTrue()
    }

    @Test
    fun `a ladder of one rung is no ladder`() {
        val loader = loader()

        loader.load(videoStream = h720, ladder = listOf(h720))

        assertThat(loaded.videoUrls()).containsExactly(h720.testUrl)
        assertThat(loader.lastSourceWasAdaptiveLadder).isFalse()
    }

    @Test
    fun `in Auto without a picked stream, YouTube's manifest plays over every stream`() {
        loader().load(videoStream = null, dashUrl = "https://m/vod.mpd")

        assertThat(loaded.kind).isEqualTo("dash")
        assertThat(loaded.uri).isEqualTo("https://m/vod.mpd")
    }

    @Test
    fun `in Auto without a manifest the current stream plays, else the first available`() {
        loader().load(videoStream = null, current = h1080)
        assertThat(loaded.videoUrls()).containsExactly(h1080.testUrl)

        loader().load(videoStream = null, current = null, available = listOf(h360, h720))
        assertThat(loaded.videoUrls()).containsExactly(h360.testUrl)
    }

    @Test
    fun `audio-only plays the audio alone and ignores every video manifest`() {
        loader().load(
            audioOnly = true,
            dashUrl = "https://m/vod.mpd",
            hlsUrl = "https://m/live.m3u8",
            isLive = true,
            ladder = listOf(h360, h720),
        )

        assertThat(loaded.kind).isEqualTo("dash")
        assertThat(loaded.uri).isEqualTo(audio.testUrl)
    }

    @Test
    fun `a live stream loads YouTube's HLS playlist`() {
        loader().load(hlsUrl = "https://m/live.m3u8", isLive = true, durationSeconds = 0)

        assertThat(loaded.kind).isEqualTo("hls")
        assertThat(loaded.uri).isEqualTo("https://m/live.m3u8")
    }

    @Test
    fun `an unknown duration falls back to the one the video carried`() {
        loader().load(durationSeconds = 0, currentDurationSeconds = 300)

        assertThat(loaded.children[0].sideloadedManifest!!.durationMs).isEqualTo(212_000L)

        loader().load(durationSeconds = 0, currentDurationSeconds = 0)
        assertThat(loaded.children[0].kind).isEqualTo("progressive")
    }

    @Test
    fun `a downloaded file plays progressive from disk with its container type`() {
        loader().load(localFilePath = "/storage/Flow/video.webm")
        assertThat(loaded.kind).isEqualTo("progressive")
        assertThat(loaded.uri).isEqualTo("file:///storage/Flow/video.webm")
        assertThat(loaded.mimeType).isEqualTo(MimeTypes.VIDEO_WEBM)

        loader().load(localFilePath = "content://media/external/video/7.mp4")
        assertThat(loaded.uri).isEqualTo("content://media/external/video/7.mp4")
        assertThat(loaded.mimeType).isEqualTo(MimeTypes.VIDEO_MP4)
    }

    @Test
    fun `nothing playable fails the load with an error`() {
        assertThat(loader().load(videoStream = null, audioStream = null, available = emptyList(), current = null)).isFalse()

        verify(exactly = 0) { player.prepare() }
        assertThat(state.value.error).isNotNull()
    }

    @Test
    fun `without a player nothing loads`() {
        assertThat(loader().load(playerArg = null)).isFalse()
    }

    @Test
    fun `every stream URL is registered with the cache under the video id`() {
        val cache = mockk<PlayerCacheManager>(relaxed = true)
        every { cache.getDataSourceFactory() } returns mockk<DataSource.Factory>(relaxed = true)
        val itVideo = h264Format(480, 1_200_000)
        val itAudio = opusFormat(bitrate = 70_000)

        loader(cache).loadMedia(
            player = player,
            context = context,
            videoStream = h720,
            audioStream = audio,
            availableVideoStreams = listOf(h1080, h360),
            currentVideoStream = h1080,
            dashManifestUrl = null,
            hlsUrl = null,
            durationSeconds = duration,
            currentDurationSeconds = 0,
            innerTubeVideoFormats = listOf(itVideo),
            innerTubeAudioFormats = listOf(itAudio),
            mediaId = "vid_1",
        )

        verify {
            cache.registerStreams(
                videoId = "vid_1",
                urls = listOf(h1080.testUrl, h360.testUrl, h720.testUrl, h1080.testUrl, audio.testUrl, itVideo.url, itAudio.url),
            )
        }
    }

    @Test
    fun `a preload source is built for the picked stream or Auto's ladder`() {
        val single = loader().buildPreloadMediaSource(context, h720, audio, listOf(h1080, h720), null, duration).shape()!!
        assertThat(single.videoUrls()).containsExactly(h720.testUrl)

        val ladder =
            loader()
                .buildPreloadMediaSource(
                    context,
                    null,
                    audio,
                    listOf(h1080, h720),
                    null,
                    duration,
                    adaptiveLadder = listOf(h720, h1080),
                ).shape()!!
        assertThat(ladder.videoUrls()).containsExactly(h720.testUrl, h1080.testUrl).inOrder()
    }

    @Test
    fun `a preload without a context builds nothing`() {
        assertThat(loader().buildPreloadMediaSource(null, h720, audio, listOf(h720), null, duration)).isNull()
    }
}
