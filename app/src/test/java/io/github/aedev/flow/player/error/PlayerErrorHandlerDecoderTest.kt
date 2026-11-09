package io.github.aedev.flow.player.error

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType

@UnstableApi
class PlayerErrorHandlerDecoderTest {
    private val events = mutableListOf<String>()
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val failedUrls = mutableSetOf<String>()
    private var currentAudio: AudioStream? = null

    private val handler =
        PlayerErrorHandler(
            appContext = mockk<Context>(relaxed = true),
            stateFlow = MutableStateFlow(EnhancedPlayerState()),
            onReloadStream = { _, _ -> },
            onQualityDowngrade = {},
            onPlaybackShutdown = { events += "shutdown" },
            onStreamExpired = {},
            onPlaybackAbandoned = {},
            onDecoderGaveUp = { events += "gave up" },
            rememberPlaybackForRecovery = { events += "remember" },
            onGatedCodecFallback = { false },
            getFailedStreamUrls = { failedUrls },
            markStreamFailed = { url -> failedUrls += url },
            incrementStreamErrors = {},
            getStreamErrorCount = { 0 },
            isAdaptiveQualityEnabled = { true },
            getManualQualityHeight = { null },
            getCurrentVideoStream = { null },
            getCurrentAudioStream = { currentAudio },
            getAvailableAudioStreams = { emptyList() },
            setCurrentAudioStream = { audio -> currentAudio = audio },
            setRecoveryState = {},
            reloadPlaybackManager = {},
        )

    private fun decoderFailure() = PlaybackException("codec died", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun audioDecoderFailure() =
        PlaybackException("MediaCodecAudioRenderer error", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun originalAac(): AudioStream =
        AudioStream
            .Builder()
            .setId("140")
            .setContent("https://example.invalid/140", true)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(129_000)
            .setAudioTrackType(AudioTrackType.ORIGINAL)
            .build()

    @Test
    fun `the first decoder failure re-prepares in place and keeps the media`() {
        assertThat(handler.handleError(decoderFailure(), player)).isTrue()

        verify(exactly = 1) { player.prepare() }
        assertThat(events).isEmpty()
    }

    @Test
    fun `giving up remembers the place before the player is emptied`() {
        handler.handleError(decoderFailure(), player)
        handler.handleError(decoderFailure(), player)

        assertThat(events).containsExactly("remember", "shutdown", "gave up").inOrder()
    }

    @Test
    fun `an audio decoder failure re-prepares in place before blaming the stream`() {
        val playing = originalAac()
        currentAudio = playing

        assertThat(handler.handleError(audioDecoderFailure(), player)).isTrue()

        verify(exactly = 1) { player.prepare() }
        assertThat(failedUrls).isEmpty()
        assertThat(currentAudio).isSameInstanceAs(playing)
    }
}
