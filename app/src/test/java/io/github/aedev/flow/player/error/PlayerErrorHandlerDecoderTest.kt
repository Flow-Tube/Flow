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

@UnstableApi
class PlayerErrorHandlerDecoderTest {
    private val events = mutableListOf<String>()
    private val player = mockk<ExoPlayer>(relaxed = true)

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
            getFailedStreamUrls = { emptySet() },
            markStreamFailed = {},
            incrementStreamErrors = {},
            getStreamErrorCount = { 0 },
            isAdaptiveQualityEnabled = { true },
            getManualQualityHeight = { null },
            getCurrentVideoStream = { null },
            getCurrentAudioStream = { null },
            getAvailableAudioStreams = { emptyList() },
            setCurrentAudioStream = {},
            setRecoveryState = {},
            reloadPlaybackManager = {},
        )

    private fun decoderFailure() = PlaybackException("codec died", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

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
}
