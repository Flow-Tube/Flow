package io.github.aedev.flow.player.error

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Every branch of [VideoErrorMapper.from] as it maps today: the string shown, the hint under it,
 * and the three flags the error panel reads. The NewPipe branches are the before-picture for the
 * native refusal messages that replace them.
 */
class VideoErrorMapperBranchesTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "s:${firstArg<Int>()}:${secondArg<Array<Any>>().joinToString()}" }
        }

    private data class Shown(
        val message: String,
        val hint: String?,
        val retryable: Boolean,
        val actionable: Boolean,
        val sessionReset: Boolean = false,
    )

    private fun shown(throwable: Throwable?): Shown =
        VideoErrorMapper.from(context, throwable).let {
            Shown(it.message, it.hint, it.isRetryable, it.isUserActionable, it.offersSessionReset)
        }

    private fun s(id: Int) = "s:$id"

    private fun fixed(
        message: Int,
        hint: Int,
        retryable: Boolean,
        actionable: Boolean,
        sessionReset: Boolean = false,
    ) = Shown(s(message), s(hint), retryable, actionable, sessionReset)

    @Test
    fun `an age restricted video says so and offers no retry`() {
        assertThat(shown(NewPipeErrorCases.ageRestricted))
            .isEqualTo(fixed(R.string.error_age_restricted, R.string.error_age_restricted_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a private video says so and offers no retry`() {
        assertThat(shown(NewPipeErrorCases.privateVideo))
            .isEqualTo(fixed(R.string.error_private_video, R.string.error_private_video_hint, retryable = false, actionable = false))
    }

    @Test
    fun `both kinds of country restriction say the region and are the user's to change`() {
        val expected = fixed(R.string.error_geo_restricted, R.string.error_geo_restricted_hint, retryable = false, actionable = true)

        assertThat(shown(NewPipeErrorCases.geographic)).isEqualTo(expected)
        assertThat(shown(NewPipeErrorCases.notInCountry)).isEqualTo(expected)
    }

    @Test
    fun `a paid video says it has to be bought`() {
        assertThat(shown(NewPipeErrorCases.paid))
            .isEqualTo(fixed(R.string.error_paid_content, R.string.error_paid_content_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a Music Premium video names Music Premium`() {
        assertThat(shown(NewPipeErrorCases.musicPremium))
            .isEqualTo(fixed(R.string.error_yt_music_premium, R.string.error_yt_music_premium_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a terminated account shows YouTube's reason when it gave one, else the stock hint`() {
        assertThat(shown(NewPipeErrorCases.terminatedWithReason))
            .isEqualTo(Shown(s(R.string.error_channel_terminated), "The account was closed", retryable = false, actionable = false))
        assertThat(shown(NewPipeErrorCases.terminatedWithoutReason))
            .isEqualTo(
                fixed(R.string.error_channel_terminated, R.string.error_channel_terminated_hint, retryable = false, actionable = false),
            )
    }

    @Test
    fun `a captcha can be retried and solved by the user`() {
        assertThat(shown(NewPipeErrorCases.reCaptcha))
            .isEqualTo(fixed(R.string.error_captcha, R.string.error_captcha_hint, retryable = true, actionable = true))
    }

    @Test
    fun `content that is not available or not supported offers no retry`() {
        assertThat(shown(NewPipeErrorCases.notAvailable))
            .isEqualTo(fixed(R.string.error_not_available, R.string.error_not_available_hint, retryable = false, actionable = false))
        assertThat(shown(NewPipeErrorCases.notSupported))
            .isEqualTo(fixed(R.string.error_not_supported, R.string.error_not_supported_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a parsing failure of the extractor is retryable`() {
        assertThat(shown(NewPipeErrorCases.extraction))
            .isEqualTo(fixed(R.string.error_extraction_failed, R.string.error_extraction_failed_hint, retryable = true, actionable = false))
    }

    @Test
    fun `each native playback block maps to its own message`() {
        fun blocked(block: PlaybackBlock) = shown(PlaybackBlockedException(block))

        assertThat(blocked(PlaybackBlock.BOT_WALL)).isEqualTo(
            fixed(
                R.string.error_bot_wall_session,
                R.string.error_bot_wall_session_hint,
                retryable = true,
                actionable = true,
                sessionReset = true,
            ),
        )
        assertThat(blocked(PlaybackBlock.REGION))
            .isEqualTo(fixed(R.string.error_geo_restricted, R.string.error_geo_restricted_hint, retryable = false, actionable = true))
        assertThat(blocked(PlaybackBlock.RATE_LIMITED))
            .isEqualTo(fixed(R.string.error_rate_limited, R.string.error_rate_limited_hint, retryable = true, actionable = true))
        assertThat(blocked(PlaybackBlock.MEMBERS_OR_PAID))
            .isEqualTo(fixed(R.string.error_members_or_paid, R.string.error_members_or_paid_hint, retryable = false, actionable = false))
        assertThat(blocked(PlaybackBlock.LOGIN_REQUIRED))
            .isEqualTo(fixed(R.string.error_login_required, R.string.error_login_required_hint, retryable = true, actionable = false))
        assertThat(blocked(PlaybackBlock.NETWORK_WALL).message).isEqualTo(s(R.string.error_bot_wall))
        assertThat(blocked(PlaybackBlock.NETWORK_WALL).sessionReset).isTrue()
    }

    @Test
    fun `a video gone on every client reads as a network failure because it is an IOException`() {
        assertThat(shown(VideoUnavailableException("abc")))
            .isEqualTo(fixed(R.string.error_network, R.string.error_network_hint, retryable = true, actionable = true))
    }

    @Test
    fun `connectivity failures say what broke`() {
        assertThat(shown(UnknownHostException("www.youtube.com")))
            .isEqualTo(fixed(R.string.error_no_internet, R.string.error_no_internet_hint, retryable = true, actionable = true))
        assertThat(shown(SocketTimeoutException()))
            .isEqualTo(fixed(R.string.error_timeout, R.string.error_timeout_hint, retryable = true, actionable = true))
        assertThat(shown(IOException("reset")))
            .isEqualTo(fixed(R.string.error_network, R.string.error_network_hint, retryable = true, actionable = true))
        assertThat(shown(RuntimeException("wrapped", IOException("reset"))))
            .isEqualTo(fixed(R.string.error_network, R.string.error_network_hint, retryable = true, actionable = true))
    }

    @Test
    fun `a coroutine timeout is a timeout`() {
        val timeout =
            runCatching { runBlocking { withTimeout(1) { kotlinx.coroutines.delay(1_000) } } }
                .exceptionOrNull() as TimeoutCancellationException

        assertThat(shown(timeout))
            .isEqualTo(fixed(R.string.error_timeout, R.string.error_timeout_hint, retryable = true, actionable = true))
        assertThat(VideoErrorMapper.fromTimeout(context).message).isEqualTo(s(R.string.error_timeout))
    }

    private fun httpStatus(code: Int): ExoPlaybackException =
        ExoPlaybackException.createForSource(
            HttpDataSource.InvalidResponseCodeException(
                code,
                "status $code",
                null,
                emptyMap(),
                DataSpec(mockk(relaxed = true)),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

    @Test
    fun `a player source error always carries an IOException, so even a 403 or a 500 reads as a network failure`() {
        val network = fixed(R.string.error_network, R.string.error_network_hint, retryable = true, actionable = true)

        assertThat(shown(httpStatus(403))).isEqualTo(network)
        assertThat(shown(httpStatus(500))).isEqualTo(network)
        assertThat(shown(ExoPlaybackException.createForSource(IOException("source"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
            .isEqualTo(network)
    }

    @Test
    fun `a renderer failure is a codec issue and any other player failure is a playback failure`() {
        val renderer =
            ExoPlaybackException.createForRenderer(
                IllegalStateException("codec"),
                "MediaCodecVideoRenderer",
                0,
                null,
                C.FORMAT_HANDLED,
                false,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )

        assertThat(shown(renderer))
            .isEqualTo(fixed(R.string.error_codec_issue, R.string.error_codec_issue_hint, retryable = true, actionable = true))
        assertThat(shown(ExoPlaybackException.createForUnexpected(RuntimeException("x"), PlaybackException.ERROR_CODE_UNSPECIFIED)))
            .isEqualTo(fixed(R.string.error_playback_failed, R.string.error_playback_failed_hint, retryable = true, actionable = false))
    }

    @Test
    fun `anything else is generic and names its type`() {
        assertThat(shown(IllegalStateException("boom")))
            .isEqualTo(Shown(s(R.string.error_generic), "IllegalStateException: boom", retryable = true, actionable = false))
        assertThat(shown(IllegalStateException()).hint)
            .isEqualTo("Error type: IllegalStateException. Please try again or restart the app.")
        assertThat(shown(null))
            .isEqualTo(fixed(R.string.error_generic, R.string.error_generic_hint, retryable = true, actionable = false))
    }
}
