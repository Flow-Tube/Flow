package io.github.aedev.flow.ui.components.videoplayer.sheet

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.player.stream.ChapterSnapshot
import io.github.aedev.flow.player.stream.chapterSnapshots
import io.github.aedev.flow.player.stream.chapterStartMs
import io.github.aedev.flow.player.stream.scrubInfoFor
import io.github.aedev.flow.player.stream.stateWithChapters
import io.github.aedev.flow.ui.screens.player.state.applyChapters
import org.junit.Test

/** Chapters from the watch response, as the player state, the chapter sheet and the scrub read-out see them. */
class ChapterPositionsTest {
    private val chapters =
        listOf(
            VideoChapter(title = "Intro", startTimeSeconds = 0, thumbnailUrl = "https://i.ytimg.com/intro.jpg"),
            VideoChapter(title = "The build", startTimeSeconds = 61),
            VideoChapter(title = "Results", startTimeSeconds = 305, thumbnailUrl = ""),
        )

    @Test
    fun `the state keeps every chapter's title, start and preview in order`() {
        assertThat(stateWithChapters(chapters).chapterSnapshots())
            .containsExactly(
                ChapterSnapshot("Intro", 0, "https://i.ytimg.com/intro.jpg"),
                ChapterSnapshot("The build", 61, null),
                ChapterSnapshot("Results", 305, ""),
            ).inOrder()
    }

    @Test
    fun `applying chapters replaces the previous ones`() {
        val replaced = stateWithChapters(chapters).applyChapters(listOf(VideoChapter(title = "Only", startTimeSeconds = 12)))
        assertThat(replaced.chapterSnapshots()).containsExactly(ChapterSnapshot("Only", 12, null))
        assertThat(stateWithChapters(chapters).applyChapters(emptyList()).chapterSnapshots()).isEmpty()
    }

    @Test
    fun `the sheet seeks to a chapter's start in whole seconds`() {
        assertThat(chapterStartMs(chapters, 0)).isEqualTo(0L)
        assertThat(chapterStartMs(chapters, 1)).isEqualTo(61_000L)
        assertThat(chapterStartMs(chapters, 2)).isEqualTo(305_000L)
    }

    @Test
    fun `an index past the list has no start`() {
        assertThat(chapterStartMs(chapters, 3)).isNull()
        assertThat(chapterStartMs(chapters, -1)).isNull()
        assertThat(chapterStartMs(emptyList(), 0)).isNull()
    }

    @Test
    fun `the scrub read-out names the chapter that has started`() {
        assertThat(scrubInfoFor(60_999L, chapters).chapterTitle).isEqualTo("Intro")
        assertThat(scrubInfoFor(61_000L, chapters).chapterTitle).isEqualTo("The build")
        assertThat(scrubInfoFor(10_000_000L, chapters).chapterTitle).isEqualTo("Results")
        assertThat(scrubInfoFor(1_000L, emptyList()).chapterTitle).isNull()
    }
}
