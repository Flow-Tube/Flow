package io.github.aedev.flow.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActiveMiniPlayerTest {
    private fun resolve(
        hasVideo: Boolean = false,
        videoVisible: Boolean = false,
        videoInBackground: Boolean = false,
        onShortsPlayer: Boolean = false,
        hasMusic: Boolean = false,
        musicSuppressed: Boolean = false,
        remoteSession: Boolean = false,
    ) = resolveActiveMiniPlayer(hasVideo, videoVisible, videoInBackground, onShortsPlayer, hasMusic, musicSuppressed, remoteSession)

    @Test
    fun `a video on the controlled device shows the remote bar over the music this device holds`() {
        assertThat(resolve(remoteSession = true)).isEqualTo(ActiveMiniPlayer.Remote)
        assertThat(resolve(remoteSession = true, hasMusic = true)).isEqualTo(ActiveMiniPlayer.Remote)
        assertThat(resolve(remoteSession = true, hasVideo = true, videoVisible = true)).isEqualTo(ActiveMiniPlayer.Video)
    }

    @Test
    fun `nothing playing shows nothing`() {
        assertThat(resolve()).isEqualTo(ActiveMiniPlayer.None)
    }

    @Test
    fun `a visible video shows the video player`() {
        assertThat(resolve(hasVideo = true, videoVisible = true)).isEqualTo(ActiveMiniPlayer.Video)
    }

    @Test
    fun `a video in the background shows the bar`() {
        assertThat(resolve(hasVideo = true, videoInBackground = true)).isEqualTo(ActiveMiniPlayer.VideoBar)
    }

    @Test
    fun `a video collapsed to the audio bar shows the bar, not the hidden video player`() {
        assertThat(resolve(hasVideo = true, videoVisible = false, videoInBackground = true)).isEqualTo(ActiveMiniPlayer.VideoBar)
        assertThat(resolve(hasVideo = true, videoVisible = true, videoInBackground = true)).isEqualTo(ActiveMiniPlayer.VideoBar)
    }

    @Test
    fun `music shows its bar only with no video cached`() {
        assertThat(resolve(hasMusic = true)).isEqualTo(ActiveMiniPlayer.Music)
        assertThat(resolve(hasMusic = true, hasVideo = true, videoInBackground = true)).isEqualTo(ActiveMiniPlayer.VideoBar)
        assertThat(resolve(hasMusic = true, musicSuppressed = true)).isEqualTo(ActiveMiniPlayer.None)
    }

    @Test
    fun `the Shorts player hides the video bar`() {
        assertThat(resolve(hasVideo = true, videoInBackground = true, onShortsPlayer = true)).isEqualTo(ActiveMiniPlayer.None)
    }

    @Test
    fun `the restored setting only hides a restored queue`() {
        assertThat(hidesRestoredMusicMiniPlayer(showRestored = false, isRestoredQueue = true)).isTrue()
        assertThat(hidesRestoredMusicMiniPlayer(showRestored = false, isRestoredQueue = false)).isFalse()
        assertThat(hidesRestoredMusicMiniPlayer(showRestored = true, isRestoredQueue = true)).isFalse()
        assertThat(hidesRestoredMusicMiniPlayer(showRestored = null, isRestoredQueue = true)).isFalse()
    }
}
