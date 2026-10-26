package io.github.aedev.flow.ui.screens.player.dialogs

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.DownloadDialogStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.components.videoplayer.settings.PlayerSettingsPage
import io.github.aedev.flow.ui.screens.player.fakePlayerState
import io.github.aedev.flow.ui.screens.player.fakeUiState
import io.github.aedev.flow.ui.screens.player.fakeVideo
import io.github.aedev.flow.ui.screens.player.relaxedVideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import io.github.aedev.flow.ui.screens.player.state.rememberVideoPlayerPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins which surface each [PlayerSheet] mounts through [PlayerDialogsContainer], the cast picker
 * included: it used to be written by the settings sheet's cast row and read by nobody.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PlayerDialogsContainerTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private fun setContainer(
        screenState: PlayerScreenState,
        playerState: EnhancedPlayerState = fakePlayerState(),
    ) {
        val video = fakeVideo()
        rule.setContent {
            MaterialTheme {
                PlayerDialogsContainer(
                    screenState = screenState,
                    playerState = playerState,
                    uiState = fakeUiState(video = video),
                    video = video,
                    viewModel = relaxedVideoPlayerViewModel(),
                    prefs = rememberVideoPlayerPreferences(LocalContext.current),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun waitForText(id: Int) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setDownloadDialogStyle(style: DownloadDialogStyle) {
        runBlocking { PlayerPreferences(context).setDownloadDialogStyle(style) }
    }

    @Test
    fun rendersNothingWhenNoFlagIsSet() {
        setContainer(PlayerScreenState())

        rule.onRoot().onChildren().assertCountEquals(0)
    }

    @Test
    fun settingsSheetMountsOnItsMainPage() {
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Settings()) })

        rule.onNodeWithText(string(R.string.player_settings)).assertExists()
    }

    @Test
    fun settingsSheetOpensStraightOnTheQualityPage() {
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Settings(PlayerSettingsPage.Quality)) })

        rule.onNodeWithText(string(R.string.video_quality_title)).assertExists()
        rule.onNodeWithText(string(R.string.player_settings)).assertDoesNotExist()
    }

    @Test
    fun downloadSheetMountsTheClassicSheetWhenPreferred() {
        setDownloadDialogStyle(DownloadDialogStyle.FULL)
        // No caption tracks: the Subtitles row is a text field, which never idles in a Dialog under
        // Robolectric. Device twin: DownloadDialogsInstrumentedTest.
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Download) }, fakePlayerState(availableSubtitles = emptyList()))

        waitForText(R.string.no_download_streams)
        rule.onNodeWithText(string(R.string.ui_try_sabr_download)).assertExists()
    }

    @Test
    @Ignore(
        "Any Compose text field inside a Dialog window never reaches idle under Robolectric with " +
            "Compose 1.13.0-alpha01; DownloadQualityDialogCompact's title field trips it. " +
            "Device twin: DownloadDialogsInstrumentedTest.",
    )
    fun downloadSheetMountsTheCompactDialogWhenPreferred() {
        setDownloadDialogStyle(DownloadDialogStyle.COMPACT)
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Download) })

        waitForText(R.string.download_video)
        rule.onNodeWithText(string(R.string.download_title_label)).assertExists()
    }

    @Test
    fun dlnaSheetMountsTheDevicePicker() {
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Dlna) })

        waitForText(R.string.dlna_cast_to_device)
        rule.onNodeWithText(string(R.string.dlna_cast_to_device)).assertExists()
    }

    @Test
    fun theSubtitleStylePageOpensInsideTheSettingsSheet() {
        setContainer(PlayerScreenState().apply { open(PlayerSheet.Settings(PlayerSettingsPage.SubtitleStyle)) })

        waitForText(R.string.subtitle_style)
        rule.onNodeWithText(string(R.string.subtitle_customization_title)).assertExists()
        rule.onNodeWithContentDescription(string(R.string.back)).assertExists()
    }
}
