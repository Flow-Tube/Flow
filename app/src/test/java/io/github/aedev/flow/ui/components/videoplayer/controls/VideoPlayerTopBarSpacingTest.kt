package io.github.aedev.flow.ui.components.videoplayer.controls

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerOverlayPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The top row's icons are asked to sit on an even rhythm (#977).
 *
 * Uniform `Arrangement.spacedBy` is not enough on its own: the gap a viewer sees is the arrangement
 * plus whatever inset each child leaves around its own glyph, so a child that sizes itself
 * differently from its neighbours reads as a wider or narrower gap. The toggles in particular go
 * through a different Material component from the plain actions, and one of them takes a hand-built
 * long-press branch, so this measures the rendered boxes rather than trusting the arrangement.
 *
 * The window is a phone in landscape fullscreen, which is where the row was reported. It is sized
 * deliberately: the right-hand cluster is laid out unbounded while the left one carries the weight,
 * so in a window too narrow for every enabled action the left cluster is measured to nothing and
 * the last button is clipped rather than the row wrapping or overflowing. That is reachable in
 * portrait with several optional actions switched on, and is a separate problem from the spacing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w891dp-h411dp-land")
class VideoPlayerTopBarSpacingTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val actionButtonSize = 40.dp
    private val actionSpacing = 8.dp

    private fun setTopBar() {
        rule.setContent {
            MaterialTheme {
                VideoPlayerTopBar(
                    preferences =
                        PlayerOverlayPreferences(
                            castEnabled = true,
                            captionsEnabled = true,
                            pipEnabled = true,
                            autoplayEnabled = true,
                            sleepTimerEnabled = true,
                        ),
                    isFullscreen = true,
                    isPortraitFullscreen = false,
                    videoTitle = null,
                    channelName = null,
                    resizeMode = 0,
                    resizeModeLabels = listOf("Fit", "Fill", "Zoom"),
                    isPipSupported = true,
                    sbSubmitEnabled = true,
                    isSponsorBlockAvailable = true,
                    isSponsorBlockOffForVideo = false,
                    isCasting = false,
                    isSubtitlesEnabled = false,
                    isAutoplayOn = true,
                    isLooping = false,
                    isSleepTimerActive = false,
                    lockModeEnabled = true,
                    isLiveChatAvailable = false,
                    hasQueue = true,
                    topPadding = 0.dp,
                    horizontalPadding = { 12.dp },
                    verticalPadding = 8.dp,
                    rowMinHeight = 44.dp,
                    pillHeight = 28.dp,
                    actionButtonSize = actionButtonSize,
                    actionIconSize = 24.dp,
                    actionSpacing = actionSpacing,
                    actions = PlayerControlActions(),
                )
            }
        }
    }

    /** Every action in the row, left to right, by the description it exposes. */
    private fun rowDescriptions(): List<String> =
        listOf(
            context.getString(R.string.btn_minimize),
            context.getString(R.string.pip_mode),
            context.getString(R.string.sb_submit_dialog_title),
            context.getString(R.string.resize_to, "Fit"),
            context.getString(R.string.playlist_queue),
            context.getString(R.string.player_settings_sponsorblock_video),
            context.getString(R.string.cast_to_tv),
            context.getString(R.string.captions),
            context.getString(R.string.autoplay),
            context.getString(R.string.sleep_timer),
            context.getString(R.string.player_lock_controls),
            context.getString(R.string.settings),
        )

    @Test
    fun `every action in the top row is the same size`() {
        setTopBar()

        val widths =
            rowDescriptions().associateWith { description ->
                rule
                    .onNodeWithContentDescription(description)
                    .fetchSemanticsNode()
                    .size.width
            }

        val expected = with(rule.density) { actionButtonSize.roundToPx() }
        assertThat(widths).containsExactlyEntriesIn(rowDescriptions().associateWith { expected })
    }

    @Test
    fun `the gap between neighbouring actions in each cluster is even`() {
        setTopBar()

        val bounds =
            rowDescriptions()
                .map { description ->
                    rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                }.sortedBy { it.left }

        val gaps = bounds.zipWithNext { left, right -> right.left - left.right }
        // The row is two clusters pushed apart, so one gap is the space between them. Every other
        // gap is a neighbour gap and they all have to match.
        val neighbourGaps = gaps.sorted().dropLast(1)
        val expected = with(rule.density) { actionSpacing.toPx() }

        neighbourGaps.forEach { gap ->
            assertThat(gap).isWithin(1f).of(expected)
        }
    }
}
