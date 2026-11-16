package io.github.aedev.flow.player.audio

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class VideoMutePauseTest {
    private var playing = true
    private val player =
        mockk<Player>(relaxed = true) {
            every { isPlaying } answers { playing }
        }
    private val pause = VideoMutePause(player).apply { enabled = true }

    @Test
    fun `muting pauses the video and sound coming back resumes it`() {
        pause.onDeviceVolumeChanged(volume = 0, muted = false)
        playing = false
        pause.onDeviceVolumeChanged(volume = 5, muted = false)

        verify(exactly = 1) { player.pause() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `a video paused by hand stays paused when the volume comes back`() {
        playing = false
        pause.onDeviceVolumeChanged(volume = 0, muted = true)
        pause.onDeviceVolumeChanged(volume = 5, muted = false)

        verify(exactly = 0) { player.pause() }
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `playing again while muted forgets the mute pause`() {
        pause.onDeviceVolumeChanged(volume = 0, muted = true)
        pause.onPlayWhenReadyChanged(playWhenReady = true, reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        playing = false
        pause.onDeviceVolumeChanged(volume = 5, muted = false)

        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `nothing happens while the setting is off`() {
        pause.enabled = false
        pause.onDeviceVolumeChanged(volume = 0, muted = true)

        verify(exactly = 0) { player.pause() }
    }
}
