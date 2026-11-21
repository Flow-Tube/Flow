package io.github.aedev.flow.ui.components.shared.card

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.ui.components.shared.WatchProgressBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The store every card reads its progress bar from, and the bar it hands the value to. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class WatchProgressTest {
    @get:Rule
    val rule = createComposeRule()

    private fun entry(
        id: String,
        position: Long,
        duration: Long,
    ) = WatchProgress(
        videoId = id,
        position = position,
        duration = duration,
        timestamp = 0L,
    )

    private fun List<WatchProgress>.toWatchProgressMap(countImported: Boolean = false): Map<String, Float> =
        mapNotNull { row -> row.cardProgress(countImported)?.let { row.videoId to it } }.toMap()

    @Test
    fun `a part-watched video maps to its fraction`() {
        val map = listOf(entry("a", position = 30_000, duration = 120_000)).toWatchProgressMap()

        assertThat(map["a"]).isWithin(TOLERANCE).of(0.25f)
    }

    @Test
    fun `a barely-started video is left out`() {
        val map = listOf(entry("a", position = 1_000, duration = 120_000)).toWatchProgressMap()

        assertThat(map).isEmpty()
    }

    @Test
    fun `a nearly-finished video fills the bar`() {
        val map = listOf(entry("a", position = 115_000, duration = 120_000)).toWatchProgressMap()

        assertThat(map["a"]).isEqualTo(1f)
    }

    @Test
    fun `an entry with no duration is left out`() {
        val map = listOf(entry("a", position = 30_000, duration = 0)).toWatchProgressMap()

        assertThat(map).isEmpty()
    }

    @Test
    fun `a card reads its own entry out of the shared store`() {
        val entries =
            mutableStateOf(
                CardProgressRows(
                    mapOf(
                        "a" to entry("a", position = 30_000, duration = 120_000),
                        "b" to entry("b", position = 90_000, duration = 120_000),
                    ),
                ),
            )
        val store = VideoWatchProgressStore(entries)
        var forA: Float? = null
        var forMissing: Float? = null

        rule.setContent {
            CompositionLocalProvider(LocalVideoWatchProgress provides store) {
                forA = rememberWatchProgress("a")
                forMissing = rememberWatchProgress("zzz")
            }
        }
        rule.waitForIdle()

        assertThat(forA).isEqualTo(0.25f)
        assertThat(forMissing).isNull()
    }

    @Test
    fun `a card picks up a progress write without being rebuilt`() {
        val entries = mutableStateOf(CardProgressRows())
        val store = VideoWatchProgressStore(entries)
        var seen: Float? = null

        rule.setContent {
            CompositionLocalProvider(LocalVideoWatchProgress provides store) {
                seen = rememberWatchProgress("a")
            }
        }
        rule.waitForIdle()
        assertThat(seen).isNull()

        entries.value = CardProgressRows(mapOf("a" to entry("a", position = 60_000, duration = 120_000)))
        rule.waitForIdle()

        assertThat(seen).isEqualTo(0.5f)
    }

    @Test
    fun `the bar reports the progress it was given`() {
        rule.setContent { MaterialTheme { WatchProgressBar(progress = 0.4f) } }
        rule.waitForIdle()

        val bars =
            rule
                .onAllNodes(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(current = 0.4f, range = 0f..1f),
                    ),
                ).fetchSemanticsNodes()

        assertThat(bars).isNotEmpty()
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
