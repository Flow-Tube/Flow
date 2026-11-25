package io.github.aedev.flow.ui.screens.player.effects

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.fakeUiState
import io.github.aedev.flow.ui.screens.player.fakeVideo
import io.github.aedev.flow.ui.screens.player.relaxedVideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The player host keeps its effects composed while the sheet is collapsed and while the app is in
 * the background, so an ungated effect is work nobody can see. These pin the three gates: comments
 * wait for the expanded body, and the two polling loops wait for STARTED.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PlayerEffectGatesTest {
    @get:Rule
    val rule = createComposeRule()

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    @After
    fun tearDown() {
        unmockkObject(EnhancedPlayerManager.Companion)
    }

    private fun setContent(
        owner: TestLifecycleOwner,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
        }
        rule.waitForIdle()
    }

    // ---- (a) comments load only for a visible expanded body ----

    @Test
    fun `comments are not fetched while the player is collapsed to the mini bar`() {
        val video = fakeVideo()
        val viewModel = relaxedVideoPlayerViewModel()
        var expanded by mutableStateOf(false)

        rule.setContent {
            GlobalVideoSyncEffect(
                currentVideoId = video.id,
                currentVideo = { video },
                uiState = fakeUiState(video = video),
                commentsEnabled = true,
                expandedBodyVisible = expanded,
                viewModel = viewModel,
            )
        }
        rule.waitForIdle()

        verify(exactly = 0) { viewModel.loadComments(any()) }
        // The model sync is not part of the gate: the UI must still adopt the playing video.
        verify(atLeast = 1) { viewModel.syncWithCurrentPlayerVideo(any()) }

        expanded = true
        rule.waitForIdle()

        verify(exactly = 1) { viewModel.loadComments(video.id) }
    }

    @Test
    fun `expanding again does not re-fetch comments already held`() {
        val video = fakeVideo()
        val viewModel = relaxedVideoPlayerViewModel()
        var expanded by mutableStateOf(true)

        rule.setContent {
            GlobalVideoSyncEffect(
                currentVideoId = video.id,
                currentVideo = { video },
                uiState = fakeUiState(video = video),
                commentsEnabled = true,
                expandedBodyVisible = expanded,
                viewModel = viewModel,
            )
        }
        rule.waitForIdle()

        expanded = false
        rule.waitForIdle()
        expanded = true
        rule.waitForIdle()

        verify(exactly = 1) { viewModel.loadComments(video.id) }
    }

    // ---- (b) the premiere poll pauses in the background ----

    @Test
    fun `the upcoming refresh poll does not run below STARTED`() {
        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        val reads = AtomicInteger()
        val settled = MutableStateFlow(fakeUiState().copy(isUpcoming = false))
        val viewModel = mockk<VideoPlayerViewModel>(relaxed = true)
        // The poll body's first act is to read the state, so counting that read is what says
        // whether the loop resumed, without pinning loadVideoInfo's parameter list.
        every { viewModel.uiState } answers {
            reads.incrementAndGet()
            settled
        }

        rule.mainClock.autoAdvance = false
        setContent(owner) {
            UpcomingVideoRefreshEffect(
                videoId = "premiere",
                isUpcoming = true,
                viewModel = viewModel,
            )
        }

        rule.mainClock.advanceTimeBy(60_000L)
        rule.waitForIdle()
        assertThat(reads.get()).isEqualTo(0)

        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.mainClock.advanceTimeBy(10_000L)
        rule.waitForIdle()

        assertThat(reads.get()).isAtLeast(1)
    }

    // ---- (c) position tracking stops with the screen off ----

    @Test
    fun `position tracking does not poll the player below STARTED`() {
        val owner = TestLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        val manager = mockk<EnhancedPlayerManager>(relaxed = true)
        // Reaching for the player is the poll; returning none keeps the assertion on the loop
        // rather than on what it reads out of a prepared ExoPlayer.
        every { manager.getPlayer() } returns null
        mockkObject(EnhancedPlayerManager.Companion)
        every { EnhancedPlayerManager.getInstance() } returns manager

        setContent(owner) {
            PositionTrackingEffect(
                isPlaying = true,
                screenState = PlayerScreenState(),
                showsPreciseProgress = false,
            )
        }

        verify(exactly = 0) { manager.getPlayer() }

        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()

        // The first read happens as soon as the window opens, which is also what picks up a
        // notification seek made while the app was backgrounded.
        verify(atLeast = 1) { manager.getPlayer() }
    }
}
