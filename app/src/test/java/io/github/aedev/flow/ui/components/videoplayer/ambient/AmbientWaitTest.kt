package io.github.aedev.flow.ui.components.videoplayer.ambient

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** #2259: a Shorts page with no player passed a flow that ends after `false`, and the wait threw. */
@OptIn(ExperimentalCoroutinesApi::class)
class AmbientWaitTest {
    @Test
    fun `an ended not-playing flow with no player holds instead of throwing`() =
        runTest {
            var failure: Throwable? = null
            val wait =
                launch {
                    runCatching { awaitPlayingOrNewFrame(flowOf(false), renderedFrames = null) }
                        .onFailure { failure = it }
                }
            advanceTimeBy(60_000)
            runCurrent()

            assertThat(wait.isActive).isTrue()
            wait.cancel()
            runCurrent()
            assertThat(failure).isNotInstanceOf(NoSuchElementException::class.java)
        }

    @Test
    fun `returns once playback starts`() =
        runTest {
            val playing = MutableStateFlow(false)
            val wait = launch { awaitPlayingOrNewFrame(playing, renderedFrames = null) }
            runCurrent()
            assertThat(wait.isActive).isTrue()

            playing.value = true
            runCurrent()

            assertThat(wait.isCompleted).isTrue()
        }

    @Test
    fun `returns on a new frame while paused`() =
        runTest {
            val frames = MutableSharedFlow<Unit>()
            val wait = launch { awaitPlayingOrNewFrame(MutableStateFlow(false), frames) }
            runCurrent()
            assertThat(wait.isActive).isTrue()

            frames.emit(Unit)
            runCurrent()

            assertThat(wait.isCompleted).isTrue()
        }
}
