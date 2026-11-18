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
import io.github.aedev.flow.player.stream.RefusalKind
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.github.aedev.flow.player.stream.YouTubeRefusal
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
 * Every branch of [VideoErrorMapper.from]: the string shown, the hint under it, and the three
 * flags the error panel reads.
 */
class VideoErrorMapperBranchesTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "s:${firstArg<Int>()}:${secondArg<Array<Any>>().joinToString()}" }
            every { getSystemService(any<String>()) } returns null
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

    private fun refused(
        kind: RefusalKind,
        claimant: String? = null,
    ) = shown(VideoUnavailableException("abc", YouTubeRefusal(kind, claimant)))

    private fun refusedViewer(kind: RefusalKind) = shown(PlaybackBlockedException(PlaybackBlock.LOGIN_REQUIRED, YouTubeRefusal(kind)))

    // The messages the NewPipe exceptions used to carry, now read from YouTube's own refusal.
    @Test
    fun `an age restricted video says so and offers no retry`() {
        assertThat(refusedViewer(RefusalKind.AGE_RESTRICTED))
            .isEqualTo(fixed(R.string.error_age_restricted, R.string.error_age_restricted_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a private video says so and offers no retry`() {
        assertThat(refusedViewer(RefusalKind.PRIVATE))
            .isEqualTo(fixed(R.string.error_private_video, R.string.error_private_video_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a paid video says it has to be bought`() {
        assertThat(refusedViewer(RefusalKind.PAID))
            .isEqualTo(fixed(R.string.error_paid_content, R.string.error_paid_content_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a members-only video says members-only`() {
        assertThat(refusedViewer(RefusalKind.MEMBERS_ONLY))
            .isEqualTo(fixed(R.string.error_members_only, R.string.error_members_only_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a Music Premium video names Music Premium`() {
        assertThat(refusedViewer(RefusalKind.MUSIC_PREMIUM))
            .isEqualTo(fixed(R.string.error_yt_music_premium, R.string.error_yt_music_premium_hint, retryable = false, actionable = false))
    }

    // NewPipe put YouTube's English reason in the hint; the stock hint is translated.
    @Test
    fun `a terminated account says the channel is gone`() {
        assertThat(refused(RefusalKind.ACCOUNT_TERMINATED))
            .isEqualTo(
                fixed(R.string.error_channel_terminated, R.string.error_channel_terminated_hint, retryable = false, actionable = false),
            )
    }

    @Test
    fun `a copyright removal names who asked for it when YouTube says`() {
        assertThat(refused(RefusalKind.COPYRIGHT_REMOVED, claimant = "Nonami Docs"))
            .isEqualTo(
                Shown(
                    s(R.string.error_copyright_removed),
                    "s:${R.string.error_copyright_removed_hint}:Nonami Docs",
                    retryable = false,
                    actionable = false,
                ),
            )
        assertThat(refused(RefusalKind.COPYRIGHT_REMOVED))
            .isEqualTo(
                fixed(
                    R.string.error_copyright_removed,
                    R.string.error_copyright_removed_hint_unnamed,
                    retryable = false,
                    actionable = false,
                ),
            )
    }

    @Test
    fun `other removals and plain unavailability offer no retry`() {
        assertThat(refused(RefusalKind.UPLOADER_REMOVED))
            .isEqualTo(
                fixed(R.string.error_removed_by_uploader, R.string.error_removed_by_uploader_hint, retryable = false, actionable = false),
            )
        assertThat(refused(RefusalKind.POLICY_REMOVED))
            .isEqualTo(fixed(R.string.error_policy_removed, R.string.error_policy_removed_hint, retryable = false, actionable = false))
        assertThat(refused(RefusalKind.RECORDING_UNAVAILABLE))
            .isEqualTo(
                fixed(
                    R.string.error_recording_unavailable,
                    R.string.error_recording_unavailable_hint,
                    retryable = false,
                    actionable = false,
                ),
            )
        assertThat(refused(RefusalKind.UNAVAILABLE))
            .isEqualTo(fixed(R.string.error_not_available, R.string.error_not_available_hint, retryable = false, actionable = false))
    }

    @Test
    fun `a refusal reached through a wrapping exception is still named`() {
        val wrapped =
            java.io.IOException(
                "refused",
                PlaybackBlockedException(PlaybackBlock.LOGIN_REQUIRED, YouTubeRefusal(RefusalKind.PRIVATE)),
            )
        assertThat(shown(wrapped).message).isEqualTo(s(R.string.error_private_video))
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
    fun `a video gone on every client without a reason says it is not available`() {
        assertThat(shown(VideoUnavailableException("abc")))
            .isEqualTo(fixed(R.string.error_not_available, R.string.error_not_available_hint, retryable = false, actionable = false))
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
    fun `a player HTTP status is named, and a source error is the stream's unless the connection dropped`() {
        assertThat(shown(httpStatus(403)))
            .isEqualTo(fixed(R.string.error_http_forbidden, R.string.error_http_forbidden_hint, retryable = true, actionable = false))
        assertThat(shown(httpStatus(500)))
            .isEqualTo(
                Shown(
                    "s:${R.string.error_http_general}:500",
                    "s:${R.string.error_http_general_hint}:500",
                    retryable = true,
                    actionable = false,
                ),
            )
        assertThat(shown(ExoPlaybackException.createForSource(IOException("source"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
            .isEqualTo(fixed(R.string.error_stream_failed, R.string.error_stream_failed_hint, retryable = true, actionable = false))
        assertThat(
            shown(
                ExoPlaybackException.createForSource(
                    IOException("dropped", java.net.SocketException("reset")),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                ),
            ),
        ).isEqualTo(fixed(R.string.error_network, R.string.error_network_hint, retryable = true, actionable = true))
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
