package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import org.junit.Test

/**
 * Pins which chip reads which continuation, and the one case that is still decided locally.
 */
class CommentFiltersTest {
    private val options =
        listOf(
            VideoCommentSort("Top", "top_token", selected = true),
            VideoCommentSort("Newest", "newest_token", selected = false),
        )

    private fun comment(
        id: String,
        published: String = "1 day ago",
        timestamp: Boolean = false,
        pinned: Boolean = false,
    ) = Comment(
        id = id,
        author = "author",
        authorThumbnail = "",
        text = "text",
        likeCount = 0,
        publishedTime = published,
        isPinned = pinned,
        richText =
            RichText(
                text = "text",
                spans =
                    if (timestamp) {
                        listOf(RichTextSpan(start = 0, length = 4, target = RichTextTarget.Timestamp(12L)))
                    } else {
                        emptyList()
                    },
            ),
    )

    @Test
    fun `top reads the first order and newest the second`() {
        assertThat(videoCommentSortFor(options, CommentSortFilter.TOP)?.token).isEqualTo("top_token")
        assertThat(videoCommentSortFor(options, CommentSortFilter.NEWEST)?.token).isEqualTo("newest_token")
        assertThat(videoCommentSortFor(options, CommentSortFilter.TIMED)?.token).isEqualTo("newest_token")
    }

    @Test
    fun `oldest reads the chronological order because the section offers no third`() {
        assertThat(videoCommentSortFor(options, CommentSortFilter.OLDEST)?.token).isEqualTo("newest_token")
    }

    @Test
    fun `a section that offered no menu selects nothing`() {
        assertThat(videoCommentSortFor(emptyList(), CommentSortFilter.TOP)).isNull()
        assertThat(videoCommentSortFor(emptyList(), CommentSortFilter.NEWEST)).isNull()
    }

    @Test
    fun `top and newest keep the order the server returned`() {
        val comments = listOf(comment("c1", published = "2 years ago"), comment("c2", published = "1 hour ago"))

        assertThat(applyVideoCommentFilters(comments, CommentSortFilter.TOP).map { it.id })
            .containsExactly("c1", "c2")
            .inOrder()
        assertThat(applyVideoCommentFilters(comments, CommentSortFilter.NEWEST).map { it.id })
            .containsExactly("c1", "c2")
            .inOrder()
    }

    @Test
    fun `oldest reverses the loaded pages and keeps a pin first`() {
        val comments =
            listOf(
                comment("pin", published = "1 hour ago", pinned = true),
                comment("new", published = "1 hour ago"),
                comment("old", published = "2 years ago"),
            )

        assertThat(applyVideoCommentFilters(comments, CommentSortFilter.OLDEST).map { it.id })
            .containsExactly("pin", "old", "new")
            .inOrder()
    }

    @Test
    fun `the timed filter keeps only comments that point at a position`() {
        val comments = listOf(comment("plain"), comment("timed", timestamp = true))

        assertThat(applyVideoCommentFilters(comments, CommentSortFilter.TIMED).map { it.id })
            .containsExactly("timed")
    }

    @Test
    fun `the timed filter drops comments from the fallback path, which carry no spans`() {
        val fallback =
            Comment(
                id = "legacy",
                author = "author",
                authorThumbnail = "",
                text = "watch 1:57",
                likeCount = 0,
                publishedTime = "1 day ago",
            )

        assertThat(applyVideoCommentFilters(listOf(fallback), CommentSortFilter.TIMED)).isEmpty()
    }
}
