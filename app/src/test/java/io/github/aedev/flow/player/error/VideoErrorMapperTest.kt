package io.github.aedev.flow.player.error

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class VideoErrorMapperTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "string:${firstArg<Int>()}" }
            every { getSystemService(any<String>()) } returns null
        }

    @Test
    fun `a bot wall on the visitor offers a session reset instead of blaming the network`() {
        val error = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.BOT_WALL))

        assertThat(error.message).isEqualTo("string:${R.string.error_bot_wall_session}")
        assertThat(error.hint).isEqualTo("string:${R.string.error_bot_wall_session_hint}")
        assertThat(error.isRetryable).isTrue()
        assertThat(error.offersSessionReset).isTrue()
    }

    @Test
    fun `a wall that held for a fresh visitor names the network, and no VPN unless one is on (#1354)`() {
        val error = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.NETWORK_WALL))

        assertThat(error.message).isEqualTo("string:${R.string.error_bot_wall}")
        assertThat(error.hint).isEqualTo("string:${R.string.error_bot_wall_mobile_data_hint}")
        assertThat(error.offersSessionReset).isTrue()
    }

    @Test
    fun `a walled network's hint points at the other kind of network, or off the VPN (#2188)`() {
        assertThat(VideoErrorMapper.networkWallHint(onVpn = true, onWifi = true)).isEqualTo(R.string.error_bot_wall_hint)
        assertThat(VideoErrorMapper.networkWallHint(onVpn = true, onWifi = false)).isEqualTo(R.string.error_bot_wall_hint)
        assertThat(VideoErrorMapper.networkWallHint(onVpn = false, onWifi = true)).isEqualTo(R.string.error_bot_wall_network_hint)
        assertThat(VideoErrorMapper.networkWallHint(onVpn = false, onWifi = false)).isEqualTo(R.string.error_bot_wall_mobile_data_hint)
    }

    @Test
    fun `a sign-in wall says an account is needed`() {
        val error = VideoErrorMapper.from(context, PlaybackBlockedException(PlaybackBlock.LOGIN_REQUIRED))

        assertThat(error.message).isEqualTo("string:${R.string.error_login_required}")
        assertThat(error.hint).isEqualTo("string:${R.string.error_login_required_hint}")
    }

    @Test
    fun `a failure with no cause stays generic`() {
        assertThat(VideoErrorMapper.from(context, null).message).isEqualTo("string:${R.string.error_generic}")
    }
}
