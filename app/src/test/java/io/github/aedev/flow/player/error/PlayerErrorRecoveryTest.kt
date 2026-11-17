package io.github.aedev.flow.player.error

import android.content.Context
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * What the player does with a failing stream: which URL it marks failed, whether it reloads,
 * downgrades or asks for fresh streams, and the failure context a refused URL is reported with.
 */
@UnstableApi
class PlayerErrorRecoveryTest {
    private val events = mutableListOf<String>()
    private val failedUrls = mutableListOf<String>()
    private var errorCount = 0
    private var adaptive = true
    private var gatedCodecFallback = false
    private var currentVideo = RecoveryStreams.noVideo()
    private var currentAudio = RecoveryStreams.noAudio()
    private val state = MutableStateFlow(EnhancedPlayerState())
    private val player = mockk<ExoPlayer>(relaxed = true) { every { currentPosition } returns 42_000L }
    private val context = mockk<Context> { every { getString(any()) } answers { "s:${firstArg<Int>()}" } }

    private val handler =
        PlayerErrorHandler(
            appContext = context,
            stateFlow = state,
            onReloadStream = { position, reason -> events += "reload-stream $position $reason" },
            onQualityDowngrade = { events += "downgrade" },
            onPlaybackShutdown = { events += "shutdown" },
            onStreamExpired = { events += "fresh-streams" },
            onPlaybackAbandoned = { events += "abandoned" },
            onDecoderGaveUp = { events += "decoder-gave-up" },
            rememberPlaybackForRecovery = { events += "remember" },
            onGatedCodecFallback = { position ->
                events += "gated-fallback $position"
                gatedCodecFallback
            },
            getFailedStreamUrls = { failedUrls.toSet() },
            markStreamFailed = { failedUrls += it },
            incrementStreamErrors = { errorCount++ },
            getStreamErrorCount = { errorCount },
            isAdaptiveQualityEnabled = { adaptive },
            getManualQualityHeight = { null },
            getCurrentVideoStream = { currentVideo },
            getCurrentAudioStream = { currentAudio },
            getAllAudioStreams = { listOfNotNull(currentAudio) },
            setCurrentAudioStream = { currentAudio = it },
            setRecoveryState = { events += "recovering" },
            reloadPlaybackManager = { events += "reload" },
        )

    @Before
    fun setUp() {
        ClientGateTracker.clear()
    }

    @After
    fun tearDown() {
        ClientGateTracker.clear()
    }

    private class HttpStatus(
        @JvmField val responseCode: Int,
    ) : IOException("HTTP $responseCode")

    private fun badStatus(code: Int) = PlaybackException("bad status", HttpStatus(code), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

    private fun error(
        code: Int,
        message: String = "failure",
        cause: Throwable? = null,
    ) = PlaybackException(message, cause, code)

    private fun playing1080(url: String = RecoveryStreams.url(137)) {
        currentVideo = RecoveryStreams.h264(1080, url = url)
        currentAudio = RecoveryStreams.aac()
    }

    @Test
    fun `an expired URL refused with 403 marks the video failed and asks for fresh streams`() {
        val url = RecoveryStreams.url(137, expire = RecoveryStreams.PASSED_EXPIRE)
        playing1080(url)

        assertThat(handler.handleError(badStatus(403), player)).isTrue()

        assertThat(failedUrls).containsExactly(url)
        assertThat(events).containsExactly("gated-fallback 42000", "fresh-streams").inOrder()
        assertThat(state.value.recoveryAttempted).isTrue()
        assertThat(state.value.isBuffering).isTrue()
    }

    @Test
    fun `a 403 on an unattested valid URL demotes the client that minted it`() {
        playing1080(RecoveryStreams.url(137, client = "MWEB"))

        handler.handleError(badStatus(403), player)

        assertThat(ClientGateTracker.isGated("MWEB")).isTrue()
        assertThat(events).contains("fresh-streams")
    }

    @Test
    fun `a 403 the gated codec fallback absorbs marks nothing and asks for no fresh streams`() {
        gatedCodecFallback = true
        currentVideo = RecoveryStreams.av1(2160)

        assertThat(handler.handleError(badStatus(410), player)).isTrue()

        assertThat(failedUrls).isEmpty()
        assertThat(events).containsExactly("gated-fallback 42000")
    }

    @Test
    fun `a 404 marks the video failed and asks for fresh streams`() {
        playing1080()

        assertThat(handler.handleError(badStatus(404), player)).isTrue()

        assertThat(failedUrls).containsExactly(RecoveryStreams.url(137))
        assertThat(events).containsExactly("fresh-streams")
    }

    @Test
    fun `a 429 or a server error reloads in place without blaming the stream`() {
        playing1080()

        assertThat(handler.handleError(badStatus(429), player)).isTrue()
        assertThat(handler.handleError(badStatus(503), player)).isTrue()

        assertThat(failedUrls).isEmpty()
        assertThat(events).containsExactly("recovering", "reload", "recovering", "reload").inOrder()
    }

    @Test
    fun `an unhandled HTTP status is left to the error screen`() {
        playing1080()

        assertThat(handler.handleError(badStatus(400), player)).isFalse()
        assertThat(events).isEmpty()
    }

    @Test
    fun `an unrecognized container marks the video failed and downgrades`() {
        playing1080()

        handler.handleError(
            error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, "UnrecognizedInputFormatException: none of the extractors"),
            player,
        )

        assertThat(failedUrls).containsExactly(RecoveryStreams.url(137))
        assertThat(events).containsExactly("downgrade")
    }

    @Test
    fun `a corrupt stream is reloaded until the error budget runs out, then downgraded`() {
        playing1080()
        val corrupt =
            error(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                "bad NAL unit",
                ParserException.createForMalformedContainer("x", null),
            )

        handler.handleError(corrupt, player)
        handler.handleError(corrupt, player)

        assertThat(failedUrls).containsExactly(RecoveryStreams.url(137), RecoveryStreams.url(137))
        assertThat(events).containsExactly("recovering", "reload", "downgrade").inOrder()
    }

    @Test
    fun `with a manual quality a corrupt stream reloads the same quality instead of downgrading`() {
        adaptive = false
        playing1080()
        val corrupt = error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, "bad NAL unit")

        handler.handleError(corrupt, player)
        handler.handleError(corrupt, player)

        assertThat(events).containsExactly("recovering", "reload", "reload-stream 0 manual-quality-parser-error").inOrder()
    }

    @Test
    fun `a parsing error with no stream playing reloads in place`() {
        handler.handleError(error(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, "odd"), player)

        assertThat(failedUrls).isEmpty()
        assertThat(events).containsExactly("recovering", "reload").inOrder()
    }

    @Test
    fun `a network error reloads, then marks the stream failed and downgrades once the budget is spent`() {
        playing1080()
        val dropped = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "connection reset")

        handler.handleError(dropped, player)
        handler.handleError(dropped, player)

        assertThat(failedUrls).containsExactly(RecoveryStreams.url(137))
        assertThat(events).containsExactly("recovering", "reload", "downgrade").inOrder()
    }

    @Test
    fun `with a manual quality a spent network budget reloads the same quality`() {
        adaptive = false
        playing1080()
        val dropped = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, "timeout")

        handler.handleError(dropped, player)
        handler.handleError(dropped, player)

        assertThat(failedUrls).isEmpty()
        assertThat(events).containsExactly("recovering", "reload", "reload-stream 0 manual-quality-network").inOrder()
    }

    @Test
    fun `a parser error that arrives as an IO error is treated as a corrupt stream`() {
        adaptive = false
        playing1080()
        val parser = error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "ParserException: Invalid NAL length")

        handler.handleError(parser, player)
        handler.handleError(parser, player)

        assertThat(failedUrls).hasSize(2)
        assertThat(events).containsExactly("recovering", "reload", "reload-stream 0 manual-quality-parser-io").inOrder()
    }

    @Test
    fun `a 403 inside a generic IO error asks for fresh streams`() {
        playing1080(RecoveryStreams.url(137, expire = RecoveryStreams.PASSED_EXPIRE))

        handler.handleError(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "Response code: 403"), player)

        assertThat(events).containsExactly("fresh-streams")
    }

    @Test
    fun `a format the device cannot decode marks the video failed and downgrades`() {
        playing1080()

        assertThat(handler.handleError(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES), player)).isTrue()
        assertThat(handler.handleError(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED), player)).isTrue()

        assertThat(failedUrls).containsExactly(RecoveryStreams.url(137), RecoveryStreams.url(137))
        assertThat(events).containsExactly("downgrade", "downgrade")
    }
}
