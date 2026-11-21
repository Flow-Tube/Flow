package io.github.aedev.flow.ui.components.shared

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.DateContext
import io.github.aedev.flow.utils.DateContextMode
import io.github.aedev.flow.utils.DateDisplayMode
import io.github.aedev.flow.utils.DateDisplaySettings
import io.github.aedev.flow.utils.DateFormatStyle
import io.github.aedev.flow.utils.TestDateLabels
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The five date preferences are collected once at the composition root. These pin that every reader
 * takes the provided value rather than opening its own DataStore collectors — an expanded player
 * with twenty-six related cards used to run well over a hundred of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class DateDisplayProviderTest {
    @get:Rule
    val rule = createComposeRule()

    private val provided =
        DateDisplaySettings(
            globalMode = DateDisplayMode.EXACT,
            formatStyle = DateFormatStyle.ISO,
            listsMode = DateContextMode.EXACT,
        )

    private fun video() =
        Video(
            id = "abc123",
            title = "Test video",
            channelName = "Test channel",
            channelId = "chan",
            thumbnailUrl = "",
            duration = 120,
            viewCount = 1_000L,
            uploadDate = "2024-03-07",
            timestamp = 1_709_769_600_000L,
        )

    @Test
    fun `the reader takes the provided value on the very first composition`() {
        val seen = mutableListOf<DateDisplaySettings>()

        rule.setContent {
            CompositionLocalProvider(LocalDateDisplaySettings provides provided) {
                seen += rememberDateDisplaySettings()
            }
        }
        rule.waitForIdle()

        // A DataStore collect would have started on DateDisplaySettings() and only flipped on a
        // later emission, so a non-default value in the first pass is the proof that none runs.
        assertThat(seen.first()).isEqualTo(provided)
    }

    @Test
    fun `a card metadata line renders the provided mode`() {
        val video = video()
        val expected = provided.format(video.uploadDate, DateContext.LISTS, TestDateLabels, video.timestamp)

        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDateDisplaySettings provides provided) {
                    Text(videoMetadataLine(video = video, isUpcoming = false))
                }
            }
        }
        rule.waitForIdle()

        assertThat(expected).isEqualTo("2024-03-07")
        rule.onNodeWithText(expected, substring = true).assertExists()
    }

    @Test
    fun `a reader outside the provider falls back to the unconfigured defaults`() {
        var seen: DateDisplaySettings? = null

        rule.setContent { seen = rememberDateDisplaySettings() }
        rule.waitForIdle()

        assertThat(seen).isEqualTo(DateDisplaySettings())
    }
}
