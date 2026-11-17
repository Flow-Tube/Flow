package io.github.aedev.flow.player.resolver

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.stream.PlaybackTestStreams.DURATION_MS
import io.github.aedev.flow.player.stream.PlaybackTestStreams.audio
import io.github.aedev.flow.player.stream.PlaybackTestStreams.audioFormat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.av1
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.hlsVideo
import io.github.aedev.flow.player.stream.PlaybackTestStreams.m4a
import io.github.aedev.flow.player.stream.PlaybackTestStreams.muxed
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opus
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9
import io.github.aedev.flow.player.stream.testUrl
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Which Media3 source a resolved set of streams becomes. YouTube throttles a progressive URL read
 * straight, so a video-only stream with byte ranges and a duration must reach the player as a
 * generated single-representation DASH manifest; anything that cannot be described that way falls
 * back to progressive rather than failing.
 */
class VideoPlaybackResolverTest {
    private val duration = DURATION_MS / 1000

    private fun resolver() = VideoPlaybackResolver(mockk<DataSource.Factory>(relaxed = true), mockk(relaxed = true), mediaId = "vid_1")

    @Before
    fun shims() = Media3JvmShims.install()

    @After
    fun unshim() = Media3JvmShims.uninstall()

    @Test
    fun `a ranged video-only stream with a duration plays as a generated DASH manifest beside its audio`() {
        val video = h264(720, 2_500_000)
        val audio = opus()

        val shape = resolver().resolve(listOf(video), audio, null, null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("merging")
        val (videoSource, audioSource) = shape.children
        assertThat(videoSource.kind).isEqualTo("dash")
        assertThat(videoSource.uri).isEqualTo(video.testUrl)
        assertThat(videoSource.mediaId).isEqualTo("vid_1")
        val rep = videoSource.sideloadedManifest!!.representations.single()
        assertThat(rep.trackType).isEqualTo(C.TRACK_TYPE_VIDEO)
        assertThat(rep.id).isEqualTo("136")
        assertThat(rep.mimeType).isEqualTo(MimeTypes.VIDEO_MP4)
        assertThat(rep.codecs).isEqualTo("avc1.4d401f")
        assertThat(rep.bitrate).isEqualTo(2_500_000)
        assertThat(rep.width).isEqualTo(1280)
        assertThat(rep.height).isEqualTo(720)
        assertThat(rep.frameRate).isEqualTo(30f)
        assertThat(rep.baseUrl).isEqualTo(video.testUrl)
        assertThat(rep.initRange).isEqualTo("0-740")
        assertThat(rep.indexRange).isEqualTo("741-1560")
        assertThat(videoSource.sideloadedManifest!!.durationMs).isEqualTo(DURATION_MS)

        assertThat(audioSource.kind).isEqualTo("dash")
        assertThat(audioSource.uri).isEqualTo(audio.testUrl)
        val audioRep = audioSource.sideloadedManifest!!.representations.single()
        assertThat(audioRep.trackType).isEqualTo(C.TRACK_TYPE_AUDIO)
        assertThat(audioRep.id).isEqualTo("251")
        assertThat(audioRep.mimeType).isEqualTo(MimeTypes.AUDIO_WEBM)
        assertThat(audioRep.codecs).isEqualTo("opus")
        assertThat(audioRep.bitrate).isEqualTo(130_000)
        assertThat(audioRep.channels).isEqualTo(2)
        assertThat(audioRep.baseUrl).isEqualTo(audio.testUrl)
        assertThat(audioRep.initRange).isEqualTo("0-640")
        assertThat(audioRep.indexRange).isEqualTo("641-1200")
    }

    @Test
    fun `m4a audio and webm video carry their own containers into the manifests`() {
        val shape = resolver().resolve(listOf(vp9(1080, 4_000_000, fps = 60)), m4a(), null, null, duration).shape()!!

        val videoRep =
            shape.children[0]
                .sideloadedManifest!!
                .representations
                .single()
        assertThat(videoRep.mimeType).isEqualTo(MimeTypes.VIDEO_WEBM)
        assertThat(videoRep.codecs).isEqualTo("vp09.00.40.08")
        assertThat(videoRep.frameRate).isEqualTo(60f)
        val audioRep =
            shape.children[1]
                .sideloadedManifest!!
                .representations
                .single()
        assertThat(audioRep.mimeType).isEqualTo(MimeTypes.AUDIO_MP4)
        assertThat(audioRep.codecs).isEqualTo("mp4a.40.2")
    }

    @Test
    fun `without a duration the streams play progressive with their container MIME types`() {
        val video = h264(720, 2_500_000)
        val audio = m4a()

        val shape = resolver().resolve(listOf(video), audio, null, null, 0).shape()!!

        assertThat(shape.children.map { it.kind }).containsExactly("progressive", "progressive").inOrder()
        assertThat(shape.children[0].uri).isEqualTo(video.testUrl)
        assertThat(shape.children[0].mimeType).isEqualTo(MimeTypes.VIDEO_MP4)
        assertThat(shape.children[1].uri).isEqualTo(audio.testUrl)
        assertThat(shape.children[1].mimeType).isEqualTo(MimeTypes.AUDIO_MP4)
    }

    @Test
    fun `audio the itag table cannot describe falls back to progressive`() {
        val unknown = audio(audioFormat(999, "audio/mp4; codecs=\"mp4a.40.5\"", 48_000))

        val shape = resolver().resolve(emptyList(), unknown, null, null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("progressive")
        assertThat(shape.uri).isEqualTo(unknown.testUrl)
        assertThat(shape.mimeType).isEqualTo(MimeTypes.AUDIO_MP4)
    }

    @Test
    fun `a muxed stream with no separate audio plays progressive on its own`() {
        val stream = muxed(360)

        val shape = resolver().resolve(listOf(h264(720, 2_500_000), stream), null, null, null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("progressive")
        assertThat(shape.uri).isEqualTo(stream.testUrl)
    }

    @Test
    fun `with separate audio the video-only stream is preferred over a taller muxed one`() {
        val videoOnly = h264(480, 1_200_000)

        val shape = resolver().resolve(listOf(muxed(720), videoOnly), opus(), null, null, duration).shape()!!

        assertThat(shape.children[0].uri).isEqualTo(videoOnly.testUrl)
    }

    @Test
    fun `among several streams the tallest plays, then the most compatible codec, then the higher bitrate`() {
        val h264 = h264(1080, 4_500_000)
        val streams = listOf(h264(720, 2_500_000), av1(1080, 3_000_000), vp9(1080, 3_500_000), h264)

        val shape = resolver().resolve(streams, opus(), null, null, duration).shape()!!

        assertThat(shape.children[0].uri).isEqualTo(h264.testUrl)
    }

    @Test
    fun `a ladder of two or more rungs becomes one adaptive manifest`() {
        val rungs = listOf(h264(360, 700_000), h264(720, 2_500_000), h264(1080, 4_500_000))
        val resolver = resolver()

        val shape = resolver.resolve(rungs, opus(), null, null, duration, asAdaptiveLadder = true).shape()!!

        assertThat(resolver.resolvedAdaptiveLadder).isTrue()
        val ladder = shape.children[0]
        assertThat(ladder.kind).isEqualTo("dash")
        assertThat(ladder.uri).isEqualTo(rungs.first().testUrl)
        assertThat(ladder.sideloadedManifest!!.representations.map { it.height }).containsExactly(360, 720, 1080).inOrder()
        assertThat(ladder.sideloadedManifest!!.representations.map { it.baseUrl }).isEqualTo(rungs.map { it.testUrl })
        assertThat(shape.children[1].kind).isEqualTo("dash")
    }

    @Test
    fun `a ladder that cannot be described starts on its first rung alone`() {
        val rungs = listOf(h264(360, 700_000, ranged = false), h264(720, 2_500_000, ranged = false))
        val resolver = resolver()

        val shape = resolver.resolve(rungs, null, null, null, duration, asAdaptiveLadder = true).shape()!!

        assertThat(resolver.resolvedAdaptiveLadder).isFalse()
        assertThat(shape.uri).isEqualTo(rungs.first().testUrl)
    }

    @Test
    fun `the last resolve decides whether a ladder is active`() {
        val resolver = resolver()
        resolver.resolve(listOf(h264(360, 700_000), h264(720, 2_500_000)), null, null, null, duration, asAdaptiveLadder = true)

        resolver.resolve(listOf(h264(720, 2_500_000)), null, null, null, duration)

        assertThat(resolver.resolvedAdaptiveLadder).isFalse()
    }

    @Test
    fun `a live stream plays YouTube's HLS playlist at the live edge gap`() {
        val shape =
            resolver()
                .resolve(
                    listOf(h264(720, 2_500_000)),
                    opus(),
                    "https://m/dash.mpd",
                    "https://m/live.m3u8",
                    0,
                    isLiveStream = true,
                ).shape()!!

        assertThat(shape.kind).isEqualTo("hls")
        assertThat(shape.uri).isEqualTo("https://m/live.m3u8")
        assertThat(shape.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8)
        assertThat(shape.liveTargetOffsetMs).isEqualTo(PlayerConfig.LIVE_EDGE_GAP_MS)
        assertThat(shape.mediaId).isEqualTo("vid_1")
    }

    @Test
    fun `a live stream without HLS plays YouTube's DASH manifest at the live edge gap`() {
        val shape = resolver().resolve(emptyList(), null, "https://m/live.mpd", null, 0, isLiveStream = true).shape()!!

        assertThat(shape.kind).isEqualTo("dash")
        assertThat(shape.uri).isEqualTo("https://m/live.mpd")
        assertThat(shape.mimeType).isEqualTo(MimeTypes.APPLICATION_MPD)
        assertThat(shape.liveTargetOffsetMs).isEqualTo(PlayerConfig.LIVE_EDGE_GAP_MS)
        assertThat(shape.sideloadedManifest).isNull()
    }

    @Test
    fun `an HLS URL is ignored for a video that is not live`() {
        val shape = resolver().resolve(listOf(h264(720, 2_500_000)), null, null, "https://m/live.m3u8", duration).shape()!!

        assertThat(shape.kind).isEqualTo("dash")
        assertThat(shape.sideloadedManifest).isNotNull()
    }

    @Test
    fun `YouTube's DASH manifest plays in auto with several streams and the original audio`() {
        val streams = listOf(h264(360, 700_000), h264(720, 2_500_000))

        val shape =
            resolver()
                .resolve(
                    streams,
                    opus(trackId = "en.4", trackName = "English original"),
                    "https://m/vod.mpd",
                    null,
                    duration,
                ).shape()!!

        assertThat(shape.kind).isEqualTo("dash")
        assertThat(shape.uri).isEqualTo("https://m/vod.mpd")
        assertThat(shape.mimeType).isEqualTo(MimeTypes.APPLICATION_MPD)
        assertThat(shape.sideloadedManifest).isNull()
    }

    @Test
    fun `a picked quality bypasses YouTube's DASH manifest`() {
        val picked = h264(720, 2_500_000)

        val shape = resolver().resolve(listOf(picked), opus(), "https://m/vod.mpd", null, duration).shape()!!

        assertThat(shape.children[0].uri).isEqualTo(picked.testUrl)
        assertThat(shape.children[0].sideloadedManifest).isNotNull()
    }

    @Test
    fun `a dubbed audio track bypasses YouTube's DASH manifest, which only carries the default track`() {
        val dub = opus(trackId = "fr.3", trackName = "French", isAutoDubbed = true)
        val streams = listOf(h264(360, 700_000), h264(720, 2_500_000))

        val shape = resolver().resolve(streams, dub, "https://m/vod.mpd", null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("merging")
        assertThat(shape.children[1].uri).isEqualTo(dub.testUrl)
    }

    @Test
    fun `a stream delivered as HLS plays as an HLS source`() {
        val stream = hlsVideo("https://m/vod.m3u8")

        val shape = resolver().resolve(listOf(stream), null, null, null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("hls")
        assertThat(shape.uri).isEqualTo("https://m/vod.m3u8")
    }

    @Test
    fun `audio alone plays as an audio source`() {
        val audio = opus()

        val shape = resolver().resolve(emptyList(), audio, null, null, duration).shape()!!

        assertThat(shape.kind).isEqualTo("dash")
        assertThat(
            shape.sideloadedManifest!!
                .representations
                .single()
                .trackType,
        ).isEqualTo(C.TRACK_TYPE_AUDIO)
    }

    @Test
    fun `audio without a duration plays progressive`() {
        val audio = opus()

        val shape = resolver().resolve(emptyList(), audio, null, null, 0).shape()!!

        assertThat(shape.kind).isEqualTo("progressive")
        assertThat(shape.mimeType).isEqualTo(MimeTypes.AUDIO_WEBM)
    }

    @Test
    fun `nothing to play resolves to nothing`() {
        assertThat(resolver().resolve(emptyList(), null, null, null, duration)).isNull()
    }
}
