package io.github.aedev.flow.ui.components.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.state.SubtitleOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Classic and compact sheets offer the same resolutions and open on the same subtitle track. On
 * a device: the compact sheet hosts a text field inside a modal sheet's window, which never reaches
 * idle under Robolectric.
 */
class DownloadDialogsInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var showCompact by mutableStateOf(false)

    private var subtitles by mutableStateOf(emptyList<SubtitleOption>())

    private val video =
        Video(
            id = "video-1",
            title = "Fixture video",
            channelName = "Fixture channel",
            channelId = "channel-1",
            thumbnailUrl = "",
            duration = 125,
            viewCount = 1_000L,
            uploadDate = "2026-01-01",
        )

    private fun format(
        itag: Int,
        mimeType: String,
        height: Int? = null,
        width: Int? = null,
        bitrate: Int,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://example.invalid/videoplayback?itag=$itag",
        mimeType = mimeType,
        bitrate = bitrate,
        width = width,
        height = height,
        contentLength = null,
        quality = "medium",
        fps = if (height != null) 30 else null,
        qualityLabel = height?.let { "${it}p" },
        averageBitrate = bitrate,
        audioQuality = if (height == null) "AUDIO_QUALITY_MEDIUM" else null,
        approxDurationMs = null,
        audioSampleRate = if (height == null) 44_100 else null,
        audioChannels = if (height == null) 2 else null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
    )

    private val videoFormats =
        listOf(
            format(137, "video/mp4; codecs=\"avc1.640028\"", height = 1080, width = 1920, bitrate = 4_000_000),
            format(248, "video/webm; codecs=\"vp9\"", height = 1080, width = 1920, bitrate = 3_000_000),
            format(136, "video/mp4; codecs=\"avc1.4d401f\"", height = 720, width = 1280, bitrate = 2_000_000),
        )

    private val audioFormats =
        listOf(
            format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128_000),
            format(251, "audio/webm; codecs=\"opus\"", bitrate = 160_000),
        )

    private fun setDialogs() {
        rule.setContent {
            MaterialTheme {
                if (showCompact) {
                    MediaDownloadSheetCompact(
                        streamSizes = emptyMap(),
                        innerTubeVideoFormats = videoFormats,
                        innerTubeAudioFormats = audioFormats,
                        video = video,
                        subtitles = subtitles,
                        onDismiss = {},
                    )
                } else {
                    MediaDownloadSheet(
                        streamSizes = emptyMap(),
                        innerTubeVideoFormats = videoFormats,
                        innerTubeAudioFormats = audioFormats,
                        video = video,
                        subtitles = subtitles,
                        onDismiss = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun renderedHeights(): Set<Int> =
        rule
            .onAllNodes(!isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .mapNotNull { text ->
                HEIGHT_LABEL
                    .matchEntire(text.text)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }.toSet()

    @Test
    fun compactSheetOffersTheSameResolutionsAsTheClassicSheet() {
        setDialogs()
        val fullHeights = renderedHeights()

        rule.runOnUiThread { showCompact = true }
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.download_title_label)).assertExists()
        rule.onNode(hasText("1080p") and hasClickAction()).performClick()
        rule.waitForIdle()

        assertEquals(setOf(1080, 720), fullHeights)
        assertEquals(fullHeights, renderedHeights())
    }

    @Test
    fun bothMenusShowTheSubtitleRowOpenOnTheLastLanguageChosen() {
        runBlocking { PlayerPreferences(context).setLastDownloadSubtitleLanguage("en") }
        subtitles = listOf(SubtitleOption(url = "https://example.invalid/en", language = "en", label = "English", isAutoGenerated = false))
        setDialogs()
        val subtitlesLabel = context.getString(R.string.filter_subtitles)

        rule.onNodeWithText(subtitlesLabel).assertExists()
        rule.onNodeWithText("English").assertExists()

        runBlocking { PlayerPreferences(context).setLastDownloadSubtitleLanguage("") }
        rule.runOnUiThread { showCompact = true }
        rule.waitForIdle()
        rule.onNodeWithText(subtitlesLabel).assertExists()
        rule.onNodeWithText(context.getString(R.string.off)).assertExists()
    }

    private companion object {
        val HEIGHT_LABEL = Regex("""^(?:[A-Z0-9]+ )?(\d+)p$""")
    }
}
