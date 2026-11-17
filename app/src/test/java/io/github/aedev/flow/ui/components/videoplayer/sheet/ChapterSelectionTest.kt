package io.github.aedev.flow.ui.components.videoplayer.sheet

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.innertube.models.response.VideoChapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The chapter a viewer taps takes the highlight straight away (#978).
 *
 * The playhead is deliberately left where it was: the player seeks to the nearest sync point, which
 * can resolve a few hundred milliseconds short of the boundary, so for a moment the position still
 * reads as the chapter before the one tapped. Derived from the position alone, that is what put the
 * highlight on the wrong row — filled to its end — before it settled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
class ChapterSelectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val chapters =
        listOf(
            segment("Intro", 0),
            segment("Middle", 60),
            segment("Outro", 120),
        )

    private fun setSheet(positionMs: Long) {
        rule.setContent {
            MaterialTheme {
                FlowChaptersBottomSheet(
                    chapters = chapters,
                    currentPosition = positionMs,
                    durationMs = 180_000L,
                    onChapterClick = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `the chapter holding the playhead starts out selected`() {
        setSheet(positionMs = 5_000L)

        rule.onNodeWithText("Intro").assertIsSelected()
        rule.onNodeWithText("Outro").assertIsNotSelected()
    }

    @Test
    fun `a tapped chapter is selected before the playhead reports arriving`() {
        setSheet(positionMs = 5_000L)

        rule.onNodeWithText("Outro").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Outro").assertIsSelected()
        rule.onNodeWithText("Intro").assertIsNotSelected()
    }
}

private fun segment(
    title: String,
    startSeconds: Int,
) = VideoChapter(title, startSeconds)
