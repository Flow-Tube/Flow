package io.github.aedev.flow.ui.screens.player

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.videoplayer.settings.PlayerSettingsPage
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.rememberVideoPlayerPreferences
import io.github.aedev.flow.ui.utils.ProvideWindowSizeClass
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins which detail layout [EnhancedVideoPlayerScreen] picks for each window class. The window is
 * shaped through Robolectric qualifiers and classified by the real
 * [io.github.aedev.flow.ui.utils.ProvideWindowSizeClass], the same provider MainActivity installs,
 * so the size class under test is the one the app would compute.
 *
 * Related videos are deliberately absent: the grid-versus-list choice is covered by
 * `PlayerLayoutModeTest`, so here the observable is the side column, which only the WIDE layout
 * mounts.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EnhancedVideoPlayerScreenLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val video = fakeVideo()

    // The chat column starts closed (#1042); opened, its close button marks the side column.
    private fun setScreen(screenState: PlayerScreenState = PlayerScreenState().apply { showLiveChatPanel = true }) {
        val uiState = MutableStateFlow(fakeUiState(video = video, isLiveChatAvailable = true))
        val viewModel = relaxedVideoPlayerViewModel(uiState = uiState)
        rule.setContent {
            ProvideWindowSizeClass {
                MaterialTheme {
                    EnhancedVideoPlayerScreen(
                        viewModel = viewModel,
                        video = video,
                        alpha = { 1f },
                        screenState = screenState,
                        isFullscreenShown = screenState.isFullscreen,
                        prefs = rememberVideoPlayerPreferences(LocalContext.current),
                        onVideoClick = {},
                        onChannelClick = {},
                        onPlaylistClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The close button on the side column's live chat header; nothing else in the tree carries it. */
    private val sideColumnCloseChat
        get() = rule.onNodeWithContentDescription(context.getString(R.string.close))

    /** The main pane's scroller: the only scrollable that holds the title. */
    private val mainPaneScroller
        get() = rule.onNode(hasScrollAction() and hasAnyDescendant(hasText(video.title)))

    /**
     * The main pane spans the window minus the supporting pane and its spacer, the same reserve the
     * host hands the video (PlayerDetailPanes.supportingPaneReserve), and the side column starts
     * past that reserve.
     */
    private fun assertPanes(
        mainPaneWidth: Dp,
        supportingPaneStart: Dp,
    ) {
        mainPaneScroller.assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(mainPaneWidth)
        val closeLeftPx = sideColumnCloseChat.fetchSemanticsNode().boundsInRoot.left
        assertThat(closeLeftPx).isAtLeast(supportingPaneStart.value * rule.density.density)
    }

    private fun assertSingleColumn() {
        rule.onNodeWithText(video.title).assertExists()
        rule.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(video.title))).assertExists()
        sideColumnCloseChat.assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "sw411dp-w411dp-h891dp-port")
    fun phonePortraitIsOneLazyColumn() {
        setScreen()

        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = "sw411dp-w891dp-h411dp-land")
    fun phoneLandscapeStaysCompact() {
        setScreen()

        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = "sw600dp-w600dp-h960dp-port")
    fun mediumWindowHasNoSideColumn() {
        setScreen()

        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h600dp-land")
    fun mediumLandscapeWindowHasNoSideColumn() {
        setScreen()

        assertSingleColumn()
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land")
    fun expandedLandscapeWindowAddsTheSideColumn() {
        setScreen()

        rule.onNodeWithText(video.title).assertExists()
        sideColumnCloseChat.assertExists()
        assertPanes(mainPaneWidth = 896.dp, supportingPaneStart = 920.dp)
    }

    @Test
    @Config(qualifiers = "sw800dp-w840dp-h1200dp-port")
    fun expandedPortraitWindowKeepsTheSingleColumn() {
        setScreen()

        rule.onNodeWithText(video.title).assertExists()
        assertSingleColumn()
    }

    /**
     * Every surface the wide layout routes into the supporting pane shows its title past the main
     * pane's edge, and nothing of it lands in the bottom-sheet slot over the video.
     */
    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land")
    fun expandedWindowHostsTheSheetsInTheSupportingPane() {
        val screenState = PlayerScreenState()
        setScreen(screenState)
        val paneStartPx = 920f * rule.density.density

        listOf(
            PlayerSheet.Description to R.string.description,
            PlayerSheet.Chapters to R.string.chapters,
            PlayerSheet.Settings() to R.string.player_settings,
            PlayerSheet.Settings(PlayerSettingsPage.SubtitleStyle) to R.string.subtitle_style,
            PlayerSheet.SleepTimer to R.string.sleep_timer,
        ).forEach { (sheet, title) ->
            rule.runOnIdle { screenState.open(sheet) }
            rule.waitForIdle()
            val titles = rule.onAllNodesWithText(context.getString(title)).fetchSemanticsNodes()
            assertThat(titles.map { it.boundsInRoot.left }.filter { it >= paneStartPx }).isNotEmpty()
        }
    }

    @Test
    @Config(qualifiers = "sw411dp-w960dp-h700dp-land")
    fun aPhoneInAnExpandedWindowAddsTheSideColumn() {
        setScreen()

        rule.onNodeWithText(video.title).assertExists()
        sideColumnCloseChat.assertExists()
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h470dp-land")
    fun aWindowShorterThanTheMediumHeightBreakpointStaysCompact() {
        setScreen()

        assertSingleColumn()
    }
}
