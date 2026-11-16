package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.repository.LiveChatRepository
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayMessage
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * A finished stream's chat replay asks for messages only while a panel shows it and the video
 * plays, and a video without a replay asks for nothing beyond the watch response it already has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatReplayGateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: LiveChatRepository = mockk(relaxed = true)
    private val controllerScope = CoroutineScope(testDispatcher)
    private val playing = MutableStateFlow(false)
    private var positionMs = 0L
    private var seed: String? = SEED

    private val playback =
        object : ChatReplayPlayback {
            override fun isPlaying(videoId: String): Flow<Boolean> = playing

            override suspend fun positionMs(): Long = positionMs
        }

    @After
    fun tearDown() {
        controllerScope.cancel()
        unmockkAll()
    }

    private fun replayTest(body: suspend TestScope.(LiveChatController) -> Unit) =
        runTest(testDispatcher) {
            coEvery { repository.replayPage(any(), any()) } answers {
                ReplayPage(listOf(ReplayMessage(secondArg<Long>(), message("m${secondArg<Long>()}"))), "next", "seek")
            }
            val controller =
                LiveChatController(
                    repository = repository,
                    scope = controllerScope,
                    dispatcher = testDispatcher,
                    replay = ChatReplayController(repository, playback, seed = { seed }),
                )
            try {
                body(controller)
            } finally {
                controllerScope.cancel()
            }
        }

    @Test
    fun `a closed chat asks for nothing even while the video plays`() =
        replayTest { controller ->
            playing.value = true
            controller.startReplay("v1")
            advanceTimeBy(WINDOW_MS)
            runCurrent()

            assertThat(controller.isAvailable.value).isTrue()
            coVerify(exactly = 0) { repository.replayPage(any(), any()) }
        }

    @Test
    fun `an open chat on a paused video waits for play`() =
        replayTest { controller ->
            controller.startReplay("v1")
            controller.setPanelVisible(true)
            advanceTimeBy(WINDOW_MS)
            runCurrent()
            coVerify(exactly = 0) { repository.replayPage(any(), any()) }

            positionMs = 5_000L
            playing.value = true
            runCurrent()

            coVerify(exactly = 1) { repository.replayPage(SEED, 5_000L) }
            assertThat(controller.messages.value.map { it.id }).containsExactly("m5000")
        }

    @Test
    fun `pausing or closing the chat stops the requests`() =
        replayTest { controller ->
            playing.value = true
            controller.startReplay("v1")
            controller.setPanelVisible(true)
            runCurrent()
            coVerify(exactly = 1) { repository.replayPage(any(), any()) }

            playing.value = false
            positionMs = 600_000L
            advanceTimeBy(WINDOW_MS)
            runCurrent()
            coVerify(exactly = 1) { repository.replayPage(any(), any()) }

            playing.value = true
            controller.setPanelVisible(false)
            advanceTimeBy(WINDOW_MS)
            runCurrent()
            coVerify(exactly = 1) { repository.replayPage(any(), any()) }
        }

    @Test
    fun `a video without a replay shows no chat and asks nothing`() =
        replayTest { controller ->
            seed = null
            playing.value = true
            controller.startReplay("v1")
            controller.setPanelVisible(true)
            advanceTimeBy(WINDOW_MS)
            runCurrent()

            assertThat(controller.isAvailable.value).isFalse()
            coVerify(exactly = 0) { repository.replayPage(any(), any()) }
        }

    @Test
    fun `the live chat setting off skips the replay too`() =
        replayTest { controller ->
            controller.setEnabled(false)
            playing.value = true
            controller.startReplay("v1")
            controller.setPanelVisible(true)
            advanceTimeBy(WINDOW_MS)
            runCurrent()

            assertThat(controller.isAvailable.value).isFalse()
            coVerify(exactly = 0) { repository.replayPage(any(), any()) }
        }

    private companion object {
        const val SEED = "seed"
        const val WINDOW_MS = 5_000L

        fun message(id: String) = LiveChatMessage(id = id, author = "a", authorPhotoUrl = null, message = id, timestamp = null)
    }
}
