package io.github.aedev.flow.ui.screens.player

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.notification.UpcomingVideoReminderWorker
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.stream.UpcomingPremiere
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins when the player screen counts down instead of playing, and what one countdown costs: the
 * premiere probe is a network call, so a resolution that already knows the release time must not
 * enter it at all and a resolution that does not must enter it exactly once.
 *
 * The countdown's own shape is pinned in `UpcomingPremierePolicyTest`; this is the controller
 * around it — the load currency check, the state write and the reminder work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingPremiereControllerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val uiState = MutableStateFlow(VideoPlayerUiState())
    private val context: Context = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val probe: UpcomingPremiereProbe = mockk()
    private val reminderIds = MutableStateFlow<Set<String>>(emptySet())
    private val controllerScope = CoroutineScope(testDispatcher)

    @Before
    fun setUp() {
        mockkObject(GlobalPlayerState)
        mockkObject(PlayerDiagnostics)
        mockkObject(UpcomingVideoReminderWorker.Companion)
        every { PlayerDiagnostics.logWarning(any(), any()) } just Runs
        every { UpcomingVideoReminderWorker.scheduleReminder(any(), any(), any(), any(), any(), any()) } just Runs
        every { UpcomingVideoReminderWorker.cancelReminder(any(), any()) } just Runs
        every { playerPreferences.upcomingVideoReminderIds } returns reminderIds
        coEvery { probe.probe(any()) } returns UpcomingPremiere.NOT_UPCOMING
    }

    @After
    fun tearDown() {
        controllerScope.cancel()
        unmockkAll()
    }

    private val armedMetadata = mutableListOf<String>()

    private fun controller(): UpcomingPremiereController =
        UpcomingPremiereController(
            context = context,
            uiState = uiState,
            playerPreferences = playerPreferences,
            probe = probe,
            scope = controllerScope,
            isLoadCurrent = { token -> token == CURRENT_TOKEN },
            armMetadata = { videoId -> armedMetadata += videoId },
        )

    @Test
    fun `a video announcing a future release replaces playback with the countdown`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(isLoading = true, queueTitle = "Queue")

            val entered = controller().applyCountdown(upcomingVideo())

            assertThat(entered).isTrue()
            assertThat(uiState.value.isUpcoming).isTrue()
            assertThat(uiState.value.upcomingReleaseTimeMs).isEqualTo(RELEASE_MS)
            assertThat(uiState.value.isLoading).isFalse()
            assertThat(uiState.value.queueTitle).isEqualTo("Queue")
        }

    @Test
    fun `a countdown entered without a load still arms the channel row and related lane`() =
        runTest(testDispatcher) {
            val video = upcomingVideo()

            controller().applyCountdown(video)

            assertThat(armedMetadata).containsExactly(video.id)
        }

    @Test
    fun `a video with no release time still ahead is not a countdown`() =
        runTest(testDispatcher) {
            val before = uiState.value

            assertThat(controller().applyCountdown(video(VIDEO_ID))).isFalse()
            assertThat(uiState.value).isEqualTo(before)
        }

    @Test
    fun `a load whose screen already holds the premiere skips straight to the countdown`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = upcomingVideo(), isLoading = true, error = "boom")

            assertThat(controller().applyCachedCountdown(VIDEO_ID)).isTrue()
            assertThat(uiState.value.isUpcoming).isTrue()
            assertThat(uiState.value.upcomingReleaseTimeMs).isEqualTo(RELEASE_MS)
            assertThat(uiState.value.error).isNull()
        }

    @Test
    fun `a cached video that is not the one loading leaves the load alone`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = upcomingVideo(), isLoading = true)

            assertThat(controller().applyCachedCountdown("other_vid")).isFalse()
            assertThat(uiState.value.isLoading).isTrue()
            assertThat(uiState.value.isUpcoming).isFalse()
        }

    @Test
    fun `entering the countdown from a finished load keeps the lane and publishes the video`() =
        runTest(testDispatcher) {
            val related = listOf(video("rel_1"))

            val entered = controller().enterCountdown(VIDEO_ID, RELEASE_MS, related, CURRENT_TOKEN)

            assertThat(entered).isTrue()
            assertThat(uiState.value.isUpcoming).isTrue()
            assertThat(uiState.value.upcomingReleaseTimeMs).isEqualTo(RELEASE_MS)
            assertThat(uiState.value.relatedVideos.map { it.id }).containsExactly("rel_1")
            verify(exactly = 1) { GlobalPlayerState.setCurrentVideo(match { it.id == VIDEO_ID && it.isUpcoming }) }
        }

    @Test
    fun `a superseded load never enters the countdown`() =
        runTest(testDispatcher) {
            val before = uiState.value

            assertThat(controller().enterCountdown(VIDEO_ID, RELEASE_MS, emptyList(), CURRENT_TOKEN + 1L)).isTrue()
            assertThat(uiState.value).isEqualTo(before)
            verify(exactly = 0) { GlobalPlayerState.setCurrentVideo(any()) }
        }

    @Test
    fun `a flagged video with a known release time is resolved without a probe`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = upcomingVideo())

            val resolved = controller().resolve(VIDEO_ID, knownUpcoming = false)

            assertThat(resolved.isUpcoming).isTrue()
            assertThat(resolved.scheduledStartMs).isEqualTo(RELEASE_MS)
            coVerify(exactly = 0) { probe.probe(any()) }
        }

    @Test
    fun `a video nothing is known about is probed exactly once`() =
        runTest(testDispatcher) {
            coEvery { probe.probe(VIDEO_ID) } returns UpcomingPremiere(isUpcoming = true, scheduledStartMs = RELEASE_MS)

            val resolved = controller().resolve(VIDEO_ID, knownUpcoming = false)

            assertThat(resolved.isUpcoming).isTrue()
            assertThat(resolved.scheduledStartMs).isEqualTo(RELEASE_MS)
            coVerify(exactly = 1) { probe.probe(VIDEO_ID) }
        }

    @Test
    fun `a load that turns out not to be a premiere reports back without touching the state`() =
        runTest(testDispatcher) {
            val before = uiState.value

            assertThat(controller().tryEnterCountdown(VIDEO_ID, emptyList(), CURRENT_TOKEN)).isFalse()
            assertThat(uiState.value).isEqualTo(before)
            coVerify(exactly = 1) { probe.probe(VIDEO_ID) }
        }

    @Test
    fun `arming the reminder stores the id and schedules the work`() =
        runTest(testDispatcher) {
            uiState.value =
                VideoPlayerUiState(cachedVideo = upcomingVideo(), isUpcoming = true, upcomingReleaseTimeMs = RELEASE_MS)

            controller().toggleReminder()
            advanceUntilIdle()

            coVerify(exactly = 1) { playerPreferences.setUpcomingVideoReminder(VIDEO_ID, true) }
            verify(exactly = 1) {
                UpcomingVideoReminderWorker.scheduleReminder(context, VIDEO_ID, RELEASE_MS, any(), any(), any())
            }
            assertThat(uiState.value.isUpcomingReminderSet).isTrue()
        }

    @Test
    fun `disarming the reminder cancels the work it scheduled`() =
        runTest(testDispatcher) {
            uiState.value =
                VideoPlayerUiState(
                    cachedVideo = upcomingVideo(),
                    isUpcoming = true,
                    upcomingReleaseTimeMs = RELEASE_MS,
                    isUpcomingReminderSet = true,
                )

            controller().toggleReminder()
            advanceUntilIdle()

            coVerify(exactly = 1) { playerPreferences.setUpcomingVideoReminder(VIDEO_ID, false) }
            verify(exactly = 1) { UpcomingVideoReminderWorker.cancelReminder(context, VIDEO_ID) }
            assertThat(uiState.value.isUpcomingReminderSet).isFalse()
        }

    @Test
    fun `a video that is not counting down has no reminder to toggle`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = upcomingVideo(), upcomingReleaseTimeMs = RELEASE_MS)

            controller().toggleReminder()
            advanceUntilIdle()

            coVerify(exactly = 0) { playerPreferences.setUpcomingVideoReminder(any(), any()) }
        }

    @Test
    fun `the stored reminder ids follow the video the screen holds`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = upcomingVideo())

            controller().collectReminderState()
            advanceUntilIdle()
            assertThat(uiState.value.isUpcomingReminderSet).isFalse()

            reminderIds.value = setOf(VIDEO_ID)
            advanceUntilIdle()

            assertThat(uiState.value.isUpcomingReminderSet).isTrue()
        }

    private fun upcomingVideo(): Video = video(VIDEO_ID, isUpcoming = true).copy(timestamp = RELEASE_MS)

    private companion object {
        const val VIDEO_ID = "vid_premiere"
        const val CURRENT_TOKEN = 4L
        val RELEASE_MS = System.currentTimeMillis() + 3_600_000L
    }
}
