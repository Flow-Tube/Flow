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
import java.util.Locale

@UnstableApi
class PlayerErrorHandlerDecoderTest {
    private val events = mutableListOf<String>()
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val failedUrls = mutableSetOf<String>()
    private var currentAudio: AudioStream? = null
    private var allAudio: List<AudioStream> = emptyList()

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
            getAllAudioStreams = { allAudio },
            setCurrentAudioStream = { audio -> currentAudio = audio },
            setRecoveryState = {},
            reloadPlaybackManager = {},
        )

    private fun decoderFailure() = PlaybackException("codec died", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun audioDecoderFailure() =
        PlaybackException("MediaCodecAudioRenderer error", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun audio(
        id: String,
        languageTag: String,
        trackType: AudioTrackType,
        format: MediaFormat = MediaFormat.M4A,
    ): AudioStream =
        AudioStream
            .Builder()
            .setId(id)
            .setContent("https://example.invalid/$id", true)
            .setMediaFormat(format)
            .setAverageBitrate(129_000)
            .setAudioTrackType(trackType)
            .setAudioLocale(Locale.forLanguageTag(languageTag))
            .build()

    private fun originalAac(): AudioStream = audio("140-en", "en", AudioTrackType.ORIGINAL)

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

    @Test
    fun `a repeated audio decoder failure moves to the other codec of the same track`() {
        val aac = originalAac()
        val opus = audio("251-en", "en", AudioTrackType.ORIGINAL, MediaFormat.WEBMA_OPUS)
        val arabicDub = audio("140-ar", "ar", AudioTrackType.DUBBED)
        currentAudio = aac
        allAudio = listOf(arabicDub, aac, opus)

        handler.handleError(audioDecoderFailure(), player)
        handler.handleError(audioDecoderFailure(), player)

        assertThat(failedUrls).containsExactly(aac.getContent())
        assertThat(currentAudio).isSameInstanceAs(opus)
        assertThat(events).isEmpty()
    }

    @Test
    fun `an audio decoder failure with no other format of the track gives up instead of dubbing`() {
        val aac = originalAac()
        currentAudio = aac
        allAudio = listOf(audio("140-ar", "ar", AudioTrackType.DUBBED), aac)

        handler.handleError(audioDecoderFailure(), player)
        handler.handleError(audioDecoderFailure(), player)

        assertThat(currentAudio).isSameInstanceAs(aac)
        assertThat(events).containsExactly("remember", "shutdown", "gave up").inOrder()
    }
}
