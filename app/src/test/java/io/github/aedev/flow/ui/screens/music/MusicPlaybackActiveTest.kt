package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicPlaybackActiveTest {
    @Test
    fun `playing is active`() {
        assertThat(MusicPlayerUiState(isPlaying = true, playWhenReady = true).isPlaybackActive).isTrue()
    }

    @Test
    fun `a seek buffering on the way to playing stays active`() {
        val seeking = MusicPlayerUiState(isPlaying = false, isBuffering = true, playWhenReady = true)

        assertThat(seeking.isPlaybackActive).isTrue()
    }

    @Test
    fun `paused is not active, also while it buffers`() {
        assertThat(MusicPlayerUiState(isPlaying = false, playWhenReady = false).isPlaybackActive).isFalse()
        assertThat(MusicPlayerUiState(isPlaying = false, isBuffering = true, playWhenReady = false).isPlaybackActive).isFalse()
    }

    @Test
    fun `held by audio focus while ready is not active`() {
        val suppressed = MusicPlayerUiState(isPlaying = false, isBuffering = false, playWhenReady = true)

        assertThat(suppressed.isPlaybackActive).isFalse()
    }
}
