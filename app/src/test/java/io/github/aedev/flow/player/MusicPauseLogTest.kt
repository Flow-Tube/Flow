package io.github.aedev.flow.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicPauseLogTest {
    @Test
    fun `every pause reason the player reports has a name`() {
        val reasons =
            listOf(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG,
            )

        assertThat(reasons.map(::playWhenReadyReasonName).filter { it.startsWith("reason ") }).isEmpty()
        assertThat(reasons.map(::playWhenReadyReasonName).toSet()).hasSize(reasons.size)
    }

    @Test
    fun `a Bluetooth drop reads as the output disconnecting`() {
        assertThat(
            playWhenReadyReasonName(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        ).isEqualTo("audio output disconnected")
    }

    @Test
    fun `an unknown reason keeps its number`() {
        assertThat(playWhenReadyReasonName(42)).isEqualTo("reason 42")
        assertThat(suppressionReasonName(42)).isEqualTo("reason 42")
    }
}
