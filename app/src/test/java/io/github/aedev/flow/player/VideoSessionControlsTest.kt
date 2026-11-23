package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.VideoSessionControls.LoopTarget
import io.github.aedev.flow.player.VideoSessionControls.State
import org.junit.Test

class VideoSessionControlsTest {
    private fun state(
        videoLooping: Boolean = false,
        queueLooping: Boolean = false,
        queueSize: Int = 5,
    ) = State(videoLooping, queueLooping, queueShuffled = false, queueSize = queueSize)

    @Test
    fun `a queue cycles off, whole queue, this video, off`() {
        assertThat(VideoSessionControls.nextLoop(state())).isEqualTo(LoopTarget(videoLoop = false, queueLoop = true))
        assertThat(VideoSessionControls.nextLoop(state(queueLooping = true))).isEqualTo(LoopTarget(videoLoop = true, queueLoop = false))
        assertThat(VideoSessionControls.nextLoop(state(videoLooping = true))).isEqualTo(LoopTarget(videoLoop = false, queueLoop = false))
    }

    @Test
    fun `a single video toggles looping itself`() {
        assertThat(VideoSessionControls.nextLoop(state(queueSize = 1))).isEqualTo(LoopTarget(videoLoop = true, queueLoop = false))
        assertThat(VideoSessionControls.nextLoop(state(videoLooping = true, queueSize = 1)))
            .isEqualTo(LoopTarget(videoLoop = false, queueLoop = false))
    }

    @Test
    fun `looping the video wins over looping the queue`() {
        assertThat(state(videoLooping = true, queueLooping = true).repeat).isEqualTo(RepeatMode.ONE)
        assertThat(state(queueLooping = true).repeat).isEqualTo(RepeatMode.ALL)
        assertThat(state().repeat).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `a leftover queue loop without a queue reads as off`() {
        assertThat(state(queueLooping = true, queueSize = 0).repeat).isEqualTo(RepeatMode.OFF)
        assertThat(state(queueSize = 1).hasQueue).isFalse()
        assertThat(state(queueSize = 2).hasQueue).isTrue()
    }
}
