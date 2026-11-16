package io.github.aedev.flow.notification

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class NewVideoKindTest {
    private fun row(
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
        isScheduledLive: Boolean = false,
        timestamp: Long = 0L,
    ) = Video(
        id = "v",
        title = "Title",
        channelName = "Channel",
        channelId = "UC",
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0,
        uploadDate = "",
        timestamp = timestamp,
        isLive = isLive,
        isUpcoming = isUpcoming,
        isScheduledLive = isScheduledLive,
    )

    private fun entry(id: String) = SubscriptionNotifications.NewVideoEntry("Channel", "Title $id", id, null)

    private val strings: (Int, Array<Any>) -> String = { res, args ->
        val name =
            when (res) {
                R.string.notification_new_video_live -> "live"
                R.string.notification_new_video_scheduled -> "scheduled"
                R.string.notification_new_video_premiere -> "premiere"
                else -> "?"
            }
        "$name(${args.joinToString()})"
    }

    @Test
    fun `no lookup or an ordinary row stays an upload`() {
        assertThat(NewVideoKind.of(null)).isEqualTo(NewVideoKind.Upload)
        assertThat(NewVideoKind.of(row())).isEqualTo(NewVideoKind.Upload)
    }

    @Test
    fun `a live row is live`() {
        assertThat(NewVideoKind.of(row(isLive = true))).isEqualTo(NewVideoKind.Live)
    }

    @Test
    fun `an upcoming stream and an upcoming premiere carry their start`() {
        assertThat(NewVideoKind.of(row(isUpcoming = true, isScheduledLive = true, timestamp = 5L)))
            .isEqualTo(NewVideoKind.ScheduledStream(5L))
        assertThat(NewVideoKind.of(row(isUpcoming = true, timestamp = 5L))).isEqualTo(NewVideoKind.Premiere(5L))
    }

    @Test
    fun `an upcoming row with no start time stays an upload`() {
        assertThat(NewVideoKind.of(row(isUpcoming = true, isScheduledLive = true))).isEqualTo(NewVideoKind.Upload)
    }

    @Test
    fun `text puts what it is in front of the title`() {
        val time: (Long) -> String = { "T$it" }
        assertThat(NewVideoKind.Upload.text("Song", time, strings)).isEqualTo("Song")
        assertThat(NewVideoKind.Live.text("Song", time, strings)).isEqualTo("live(Song)")
        assertThat(NewVideoKind.ScheduledStream(7L).text("Song", time, strings)).isEqualTo("scheduled(T7, Song)")
        assertThat(NewVideoKind.Premiere(7L).text("Song", time, strings)).isEqualTo("premiere(T7, Song)")
    }

    @Test
    fun `start time shows the time alone today and the date otherwise`() {
        val now = LocalDateTime.of(2026, 11, 15, 9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val today = LocalDateTime.of(2026, 11, 15, 18, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val tomorrow = LocalDateTime.of(2026, 11, 16, 18, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

        val todayText = startTimeText(today, now, ZoneOffset.UTC, Locale.UK)
        val tomorrowText = startTimeText(tomorrow, now, ZoneOffset.UTC, Locale.UK)

        assertThat(todayText).isEqualTo("18:30")
        assertThat(tomorrowText).contains("18:30")
        assertThat(tomorrowText).contains("16")
    }

    @Test
    fun `classify asks once per video and keeps an upload when a lookup fails`() =
        runTest {
            val asked = mutableListOf<String>()
            val result =
                classifyNewVideos(listOf(entry("a"), entry("b"), entry("c")), Dispatchers.Unconfined) { e ->
                    asked += e.videoId
                    when (e.videoId) {
                        "a" -> row(isLive = true)
                        "b" -> error("network")
                        else -> null
                    }
                }

            assertThat(asked).containsExactly("a", "b", "c")
            assertThat(result.map { it.kind })
                .containsExactly(NewVideoKind.Live, NewVideoKind.Upload, NewVideoKind.Upload)
                .inOrder()
        }
}
