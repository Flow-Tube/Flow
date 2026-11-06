package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import org.junit.Test

class TimedCommentsTest {
    private fun comment(
        id: String,
        vararg stamps: Pair<Int, Long>,
        likes: Int = 0,
        pinned: Boolean = false,
    ) = Comment(
        id = id,
        author = "author",
        authorThumbnail = "",
        text = "text",
        likeCount = likes,
        publishedTime = "1 day ago",
        isPinned = pinned,
        richText =
            RichText(
                text = "text",
                spans = stamps.map { (start, seconds) -> RichTextSpan(start, 4, RichTextTarget.Timestamp(seconds)) },
            ),
    )

    private fun ids(comments: List<Comment>) = comments.map { it.id }

    @Test
    fun `the first timestamp in the text is the comment's position`() {
        assertThat(comment("c", 12 to 240L, 3 to 60L).firstTimestampSeconds()).isEqualTo(60L)
        assertThat(comment("plain").firstTimestampSeconds()).isNull()
    }

    @Test
    fun `the list runs latest first with no place for a comment without a timestamp`() {
        val comments = listOf(comment("early", 0 to 10L), comment("plain"), comment("late", 0 to 200L), comment("mid", 0 to 90L))

        assertThat(ids(timedCommentsInOrder(comments, durationMs = 0L))).containsExactly("late", "mid", "early").inOrder()
    }

    @Test
    fun `the same position puts the most liked first`() {
        val comments = listOf(comment("few", 0 to 30L, likes = 1), comment("many", 0 to 30L, likes = 9))

        assertThat(ids(timedCommentsInOrder(comments, durationMs = 0L))).containsExactly("many", "few").inOrder()
    }

    @Test
    fun `a pinned comment takes its place by position`() {
        val comments = listOf(comment("pin", 0 to 10L, pinned = true), comment("late", 0 to 200L))

        assertThat(ids(timedCommentsInOrder(comments, durationMs = 0L))).containsExactly("late", "pin").inOrder()
    }

    @Test
    fun `a timestamp past the end goes once the length is known`() {
        val comments = listOf(comment("past", 0 to 1_577L), comment("end", 0 to 403L))

        assertThat(ids(timedCommentsInOrder(comments, durationMs = 403_000L))).containsExactly("end")
        assertThat(ids(timedCommentsInOrder(comments, durationMs = 0L))).containsExactly("past", "end").inOrder()
    }

    @Test
    fun `marks run in playing order as fractions of the length`() {
        val comments = listOf(comment("late", 0 to 90L), comment("plain"), comment("early", 0 to 10L))

        assertThat(timedCommentMarks(comments, durationMs = 100_000L))
            .containsExactly(0.1f, 0.9f)
            .inOrder()
    }

    @Test
    fun `marks that would sit on top of each other merge into the earliest`() {
        val comments =
            listOf(
                comment("a", 0 to 100L),
                comment("same", 0 to 100L),
                comment("close", 0 to 103L),
                comment("apart", 0 to 104L),
                comment("next", 0 to 900L),
            )

        assertThat(timedCommentMarks(comments, durationMs = 1_000_000L))
            .containsExactly(0.1f, 0.104f, 0.9f)
            .inOrder()
    }

    @Test
    fun `a mark past the end goes and one on the end stays`() {
        val comments = listOf(comment("past", 0 to 1_577L), comment("end", 0 to 403L))

        assertThat(timedCommentMarks(comments, durationMs = 403_000L)).containsExactly(1f)
    }

    @Test
    fun `nothing marks a bar with no timed comments or no length`() {
        assertThat(timedCommentMarks(emptyList(), durationMs = 100_000L)).isEmpty()
        assertThat(timedCommentMarks(listOf(comment("plain")), durationMs = 100_000L)).isEmpty()
        assertThat(timedCommentMarks(listOf(comment("timed", 0 to 10L)), durationMs = 0L)).isEmpty()
    }

    @Test
    fun `the anchor is the latest timestamp already passed`() {
        val seconds = listOf(200L, 90L, 90L, 10L)

        assertThat(timedAnchorIndex(seconds, 201_000L)).isEqualTo(0)
        assertThat(timedAnchorIndex(seconds, 199_999L)).isEqualTo(1)
        assertThat(timedAnchorIndex(seconds, 90_000L)).isEqualTo(1)
        assertThat(timedAnchorIndex(seconds, 89_000L)).isEqualTo(3)
        assertThat(timedAnchorIndex(seconds, 10_000L)).isEqualTo(3)
        assertThat(timedAnchorIndex(seconds, 9_000L)).isEqualTo(-1)
        assertThat(timedAnchorIndex(emptyList(), 9_000L)).isEqualTo(-1)
    }
}
