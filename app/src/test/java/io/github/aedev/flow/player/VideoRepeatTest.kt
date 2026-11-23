package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoRepeatTest {
    @Test
    fun `repeat cycles off, all, one, off`() {
        assertThat(RepeatMode.OFF.next()).isEqualTo(RepeatMode.ALL)
        assertThat(RepeatMode.ALL.next()).isEqualTo(RepeatMode.ONE)
        assertThat(RepeatMode.ONE.next()).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `both loops off reads as off`() {
        assertThat(videoRepeatMode(queueLoop = false, videoLoop = false)).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `the queue loop alone reads as all`() {
        assertThat(videoRepeatMode(queueLoop = true, videoLoop = false)).isEqualTo(RepeatMode.ALL)
    }

    @Test
    fun `the video loop wins over the queue loop`() {
        assertThat(videoRepeatMode(queueLoop = false, videoLoop = true)).isEqualTo(RepeatMode.ONE)
        assertThat(videoRepeatMode(queueLoop = true, videoLoop = true)).isEqualTo(RepeatMode.ONE)
    }
}
