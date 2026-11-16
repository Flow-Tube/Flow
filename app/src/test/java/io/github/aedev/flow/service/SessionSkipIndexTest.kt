package io.github.aedev.flow.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionSkipIndexTest {
    private fun index(
        command: Int,
        target: Int,
        current: Int = 2,
        count: Int = 5,
        playing: Boolean = true,
    ) = sessionSkipIndex(command, target, current, count, playing)

    @Test
    fun `next and previous to another song go to the crossfade`() {
        assertThat(index(Player.COMMAND_SEEK_TO_NEXT, 3)).isEqualTo(3)
        assertThat(index(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, 3)).isEqualTo(3)
        assertThat(index(Player.COMMAND_SEEK_TO_PREVIOUS, 1)).isEqualTo(1)
        assertThat(index(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, 1)).isEqualTo(1)
    }

    @Test
    fun `a previous that restarts the song keeps the player's seek`() {
        assertThat(index(Player.COMMAND_SEEK_TO_PREVIOUS, 2)).isNull()
    }

    @Test
    fun `seeks within the song or to a picked item keep the player's seek`() {
        assertThat(index(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 2)).isNull()
        assertThat(index(Player.COMMAND_SEEK_TO_MEDIA_ITEM, 4)).isNull()
        assertThat(index(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, 0)).isNull()
    }

    @Test
    fun `nothing playing keeps the player's seek`() {
        assertThat(index(Player.COMMAND_SEEK_TO_NEXT, 3, playing = false)).isNull()
    }

    @Test
    fun `a slot outside the queue keeps the player's seek`() {
        assertThat(index(Player.COMMAND_SEEK_TO_NEXT, -1)).isNull()
        assertThat(index(Player.COMMAND_SEEK_TO_NEXT, 5)).isNull()
    }
}
