package io.github.aedev.flow.player.error

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** The failure context a refused stream is logged and retry-limited with, read off the streams in play. */
@UnstableApi
class StreamFailureContextTest {
    private var video = RecoveryStreams.noVideo()
    private var audio = RecoveryStreams.noAudio()

    private val recovery =
        StreamDenialRecovery(
            appContext = mockk<Context>(relaxed = true),
            stateFlow = MutableStateFlow(EnhancedPlayerState()),
            onPlaybackShutdown = {},
            onStreamExpired = {},
            onPlaybackAbandoned = {},
            onGatedCodecFallback = { false },
            markStreamFailed = {},
            getCurrentVideoStream = { video },
            getCurrentAudioStream = { audio },
            setRecoveryState = {},
            reloadPlaybackManager = {},
        )

    @Test
    fun `the context names the client, quality, codec, itags and MIME types in play`() {
        val url = RecoveryStreams.url(399, client = "IOS", pot = "token")
        video = RecoveryStreams.av1(1080, itag = 399, url = url)
        audio = RecoveryStreams.opus()

        val context = recovery.buildFailureContext("http-403", 403)

        assertThat(context)
            .isEqualTo(
                StreamFailureContext(
                    reason = "http-403",
                    httpCode = 403,
                    url = url,
                    videoHeight = 1080,
                    videoCodec = "av1",
                    videoItag = "399",
                    videoMimeType = "video/mp4",
                    audioItag = "251",
                    audioMimeType = "audio/webm",
                    denialKind = StreamDenialKind.TOKEN_REJECTED,
                    client = "IOS",
                ),
            )
        assertThat(context.variantKey).isEqualTo("c=IOS|1080p|av1|v=399|a=251")
    }

    @Test
    fun `an H264 video with AAC audio reads as h264 and audio mp4`() {
        video = RecoveryStreams.h264(720, itag = 136, url = RecoveryStreams.url(136, expire = RecoveryStreams.PASSED_EXPIRE))
        audio = RecoveryStreams.aac()

        val context = recovery.buildFailureContext("file-not-found")

        assertThat(context.videoCodec).isEqualTo("h264")
        assertThat(context.videoHeight).isEqualTo(720)
        assertThat(context.videoMimeType).isEqualTo("video/mp4")
        assertThat(context.audioMimeType).isEqualTo("audio/mp4")
        assertThat(context.denialKind).isEqualTo(StreamDenialKind.URL_EXPIRED)
        assertThat(context.client).isEqualTo("ANDROID_VR")
    }

    @Test
    fun `a Short in a tier NewPipe never knew still names its itag and size`() {
        video = RecoveryStreams.video(780, 1080, "video/webm; codecs=\"vp9\"", width = 608)

        val context = recovery.buildFailureContext("http-403", 403)

        assertThat(context.videoItag).isEqualTo("780")
        assertThat(context.videoHeight).isEqualTo(1080)
        assertThat(context.videoCodec).isEqualTo("vp9")
        assertThat(context.videoMimeType).isEqualTo("video/webm")
        assertThat(context.audioItag).isNull()
    }

    @Test
    fun `with nothing playing the context carries only the reason`() {
        val context = recovery.buildFailureContext("invalid-content-type")

        assertThat(context).isEqualTo(StreamFailureContext(reason = "invalid-content-type", denialKind = StreamDenialKind.UNKNOWN))
    }
}
