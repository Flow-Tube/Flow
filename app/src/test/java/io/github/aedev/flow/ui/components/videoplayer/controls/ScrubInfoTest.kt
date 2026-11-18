package io.github.aedev.flow.ui.components.videoplayer.controls

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.innertube.models.response.HeatmapHighlight
import io.github.aedev.flow.innertube.models.response.HeatmapMarker
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import org.junit.Test

private fun chapter(
    title: String,
    startSeconds: Int,
) = VideoChapter(title, startSeconds)

private fun sponsor(
    category: String,
    start: Float,
    end: Float,
) = SponsorBlockSegment(category = category, segment = listOf(start, end), uuid = "$category-$start", actionType = "skip")

private val CHAPTERS =
    listOf(
        chapter("Intro", 0),
        chapter("The build", 60),
        chapter("Results", 300),
    )

class ScrubInfoTest {
    @Test
    fun `the chapter is the last one to have started`() {
        assertThat(scrubInfoAt(0L, CHAPTERS, emptyList(), null).chapterTitle).isEqualTo("Intro")
        assertThat(scrubInfoAt(59_999L, CHAPTERS, emptyList(), null).chapterTitle).isEqualTo("Intro")
        assertThat(scrubInfoAt(60_000L, CHAPTERS, emptyList(), null).chapterTitle).isEqualTo("The build")
        assertThat(scrubInfoAt(9_000_000L, CHAPTERS, emptyList(), null).chapterTitle).isEqualTo("Results")
    }

    @Test
    fun `a video with no chapters reports none`() {
        assertThat(scrubInfoAt(1_000L, emptyList(), emptyList(), null).chapterTitle).isNull()
    }

    @Test
    fun `a blank chapter title is dropped rather than shown as an empty pill`() {
        val blank = listOf(chapter("", 0))

        assertThat(scrubInfoAt(1_000L, blank, emptyList(), null).chapterTitle).isNull()
    }

    @Test
    fun `the sponsor segment is the one the position falls inside`() {
        val segments = listOf(sponsor("sponsor", 30f, 45f))

        assertThat(scrubInfoAt(29_000L, emptyList(), segments, null).sponsorCategory).isNull()
        assertThat(scrubInfoAt(30_500L, emptyList(), segments, null).sponsorCategory).isEqualTo("sponsor")
        assertThat(scrubInfoAt(45_000L, emptyList(), segments, null).sponsorCategory).isEqualTo("sponsor")
        assertThat(scrubInfoAt(45_500L, emptyList(), segments, null).sponsorCategory).isNull()
    }

    @Test
    fun `segment boundaries are read in seconds, not milliseconds`() {
        val segments = listOf(sponsor("intro", 0f, 12.5f))

        assertThat(scrubInfoAt(12_000L, emptyList(), segments, null).sponsorCategory).isEqualTo("intro")
        assertThat(scrubInfoAt(13_000L, emptyList(), segments, null).sponsorCategory).isNull()
    }

    @Test
    fun `a nested segment wins over the one containing it`() {
        val segments =
            listOf(
                sponsor("sponsor", 10f, 120f),
                sponsor("filler", 40f, 50f),
            )

        assertThat(scrubInfoAt(45_000L, emptyList(), segments, null).sponsorCategory).isEqualTo("filler")
        assertThat(scrubInfoAt(100_000L, emptyList(), segments, null).sponsorCategory).isEqualTo("sponsor")
    }

    @Test
    fun `a whole-video label is not reported at the start of the video`() {
        val segments = listOf(sponsor("exclusive_access", 0f, 0f))

        assertThat(scrubInfoAt(0L, emptyList(), segments, null).sponsorCategory).isNull()
    }

    @Test
    fun `the most replayed label applies only inside its stretch`() {
        val heatmap =
            VideoHeatmap(
                markers = listOf(HeatmapMarker(0L, 1_000L, 1f)),
                highlights = listOf(HeatmapHighlight(20_000L, 26_000L, 22_000L, "Most replayed")),
            )

        assertThat(scrubInfoAt(22_000L, emptyList(), emptyList(), heatmap).highlightLabel).isEqualTo("Most replayed")
        assertThat(scrubInfoAt(30_000L, emptyList(), emptyList(), heatmap).highlightLabel).isNull()
    }

    @Test
    fun `a position with nothing to say reports empty`() {
        assertThat(scrubInfoAt(1_000L, emptyList(), emptyList(), null).isEmpty).isTrue()
        assertThat(scrubInfoAt(1_000L, CHAPTERS, emptyList(), null).isEmpty).isFalse()
    }

    @Test
    fun `every label can apply at once`() {
        val heatmap =
            VideoHeatmap(
                markers = listOf(HeatmapMarker(0L, 1_000L, 1f)),
                highlights = listOf(HeatmapHighlight(60_000L, 90_000L, 70_000L, "Most replayed")),
            )
        val info = scrubInfoAt(70_000L, CHAPTERS, listOf(sponsor("sponsor", 65f, 80f)), heatmap)

        assertThat(info.chapterTitle).isEqualTo("The build")
        assertThat(info.sponsorCategory).isEqualTo("sponsor")
        assertThat(info.highlightLabel).isEqualTo("Most replayed")
    }
}
