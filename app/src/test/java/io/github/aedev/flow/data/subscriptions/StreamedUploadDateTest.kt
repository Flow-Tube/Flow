package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedMerger.preservingEnrichedMetadata
import io.github.aedev.flow.utils.DateDisplayMode
import io.github.aedev.flow.utils.DateFormatStyle
import io.github.aedev.flow.utils.DateLabels
import io.github.aedev.flow.utils.formatUploadDateConfigured
import io.github.aedev.flow.utils.formatYouTubeRelativeTime
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

class StreamedUploadDateTest {
    private val now = System.currentTimeMillis()
    private val dayMs = 24L * 60L * 60L * 1000L
    private val streamedAt = now - 2 * dayMs

    @Before
    fun stubRelativeDates() {
        mockkStatic(FORMAT_UTILS)
        every { formatYouTubeRelativeTime(any(), any(), any()) } returns "2 days ago"
    }

    @After
    fun restoreRelativeDates() = unmockkStatic(FORMAT_UTILS)

    private fun row(
        uploadDate: String,
        timestamp: Long = streamedAt,
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
    ) = Video(
        id = "v1",
        title = "t",
        channelName = "c",
        channelId = "UCc",
        thumbnailUrl = "",
        duration = 5400,
        viewCount = 0,
        uploadDate = uploadDate,
        timestamp = timestamp,
        isLive = isLive,
        isUpcoming = isUpcoming,
    )

    @Test
    fun `the mark is one English word, never the live wording`() {
        assertThat(StreamedUploadDate.marked("2 days ago")).isEqualTo("Streamed 2 days ago")
        assertThat(StreamedUploadDate.marked("Streamed live on Jun 19, 2020")).isEqualTo("Streamed Jun 19, 2020")
        assertThat(StreamedUploadDate.marked("Streamed 2 days ago")).isEqualTo("Streamed 2 days ago")
        assertThat(StreamedUploadDate.marked("")).isEmpty()
        assertThat(StreamedUploadDate.isMarked("streamed 1 year ago")).isTrue()
        assertThat(StreamedUploadDate.isMarked("Streaming now")).isFalse()
        assertThat(StreamedUploadDate.unmarked("Streamed hace 2 días")).isEqualTo("hace 2 días")
    }

    @Test
    fun `a marked date sorts by its age, not as a stream on air`() {
        val day = 24L * 60L * 60L * 1000L
        val fixedNow = 1_791_331_200_000L

        assertThat(SubscriptionFeedTimestamps.parseRelativeTime("Streamed 2 days ago", fixedNow)).isEqualTo(fixedNow - 2 * day)
        assertThat(SubscriptionFeedTimestamps.isUnstableFreshUploadText("Streamed 3 hours ago")).isTrue()
    }

    @Test
    fun `recomputing the relative date keeps the mark`() {
        val shown = listOf(row("Streamed 3 days ago")).withRelativeUploadDates(now).single()

        assertThat(shown.uploadDate).isEqualTo("Streamed 2 days ago")
    }

    @Test
    fun `a stream on air again loses the mark`() {
        val shown = listOf(row("Streamed 3 days ago", isLive = true)).withRelativeUploadDates(now).single()

        assertThat(StreamedUploadDate.isMarked(shown.uploadDate)).isFalse()
    }

    @Test
    fun `the mark survives an RSS duplicate winning the metadata`() {
        val rss = row("2 days ago").copy(timestampIsExact = true)
        val tab = row("Streamed 2 days ago")

        val merged = SubscriptionFeedMerger.mergeDuplicates(listOf(rss, tab), now)

        assertThat(merged.uploadDate).isEqualTo("Streamed 2 days ago")
        assertThat(merged.timestamp).isEqualTo(streamedAt)
    }

    @Test
    fun `a refresh that only knows RSS keeps the mark the stored row had`() {
        val fresh = row("2 days ago").preservingEnrichedMetadata(row("Streamed 2 days ago"))

        assertThat(fresh.uploadDate).isEqualTo("Streamed 2 days ago")
    }

    @Test
    fun `a premiere is never marked from an older row`() {
        val upcoming = row("Premieres in 2 days", isUpcoming = true).preservingEnrichedMetadata(row("Streamed 2 days ago"))

        assertThat(upcoming.uploadDate).isEqualTo("Premieres in 2 days")
    }

    @Test
    fun `the display puts the translated word in front in every mode`() {
        val german = DateLabels(live = "LIVE", streamedTemplate = "%1\$s gestreamt", premieredTemplate = "Premiere %1\$s")

        fun shown(mode: DateDisplayMode) =
            formatUploadDateConfigured(
                date = "Streamed 2 days ago",
                mode = mode,
                style = DateFormatStyle.ISO,
                labels = german,
                timestampFallbackMs = streamedAt,
                locale = Locale.US,
                hl = "en",
                timestampIsExact = true,
                nowMillis = now,
            )

        assertThat(shown(DateDisplayMode.RELATIVE)).isEqualTo("2 days ago gestreamt")
        assertThat(shown(DateDisplayMode.EXACT)).matches("""\d{4}-\d{2}-\d{2} gestreamt""")
        assertThat(shown(DateDisplayMode.BOTH)).matches("""2 days ago gestreamt • \d{4}-\d{2}-\d{2}""")
    }

    private companion object {
        const val FORMAT_UTILS = "io.github.aedev.flow.utils.FormatUtilsKt"
    }
}
