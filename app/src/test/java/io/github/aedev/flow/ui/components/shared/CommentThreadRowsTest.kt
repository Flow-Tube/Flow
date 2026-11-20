package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import org.junit.Test

/** Pins which rows a reply tree becomes and which thread lines each row draws. */
class CommentThreadRowsTest {
    private fun node(
        id: String,
        vararg children: CommentReplyNode,
    ) = CommentReplyNode(
        comment = Comment(id = id, author = "@$id", authorThumbnail = "", text = "", likeCount = 0, publishedTime = ""),
        children = children.toList(),
    )

    private fun rows(
        vararg nodes: CommentReplyNode,
        hasMore: Boolean = false,
        unfolded: Set<String> = emptySet(),
    ) = commentThreadRows(nodes.toList(), hasMore) { it in unfolded }

    private fun CommentThreadRow.reply() = this as CommentThreadRow.Reply

    @Test
    fun `top-level replies hang off the comment's line, which stops at the last one`() {
        val result = rows(node("a"), node("b"))

        assertThat(result.map { it.key }).containsExactly("a", "b").inOrder()
        assertThat(result[0].lines).isEqualTo(CommentThreadLines(column = 1, elbowFrom = 0, through = setOf(0)))
        assertThat(result[1].lines).isEqualTo(CommentThreadLines(column = 1, elbowFrom = 0, through = emptySet()))
    }

    @Test
    fun `more replies on the server keep the line running to the load more row`() {
        val result = rows(node("a"), hasMore = true)

        assertThat(result[0].lines.through).containsExactly(0)
        val more = result.last()
        assertThat(more).isInstanceOf(CommentThreadRow.MoreReplies::class.java)
        assertThat(more.lines).isEqualTo(CommentThreadLines(column = 1, elbowFrom = 0, through = emptySet()))
    }

    @Test
    fun `a reply's own replies show at once, one column in, off the reply's avatar`() {
        val result = rows(node("a", node("a1")), node("b"))

        assertThat(result.map { it.key }).containsExactly("a", "a1", "b").inOrder()
        assertThat(result[0].lines.ownLine).isEqualTo(1)
        assertThat(result[1].reply().depth).isEqualTo(2)
        assertThat(result[1].lines).isEqualTo(CommentThreadLines(column = 2, elbowFrom = 1, through = setOf(0)))
    }

    @Test
    fun `deeper replies fold behind a count until unfolded`() {
        val tree = node("a", node("a1", node("a11", node("a111")), node("a12")))

        val folded = rows(tree)
        assertThat(folded.map { it.key }).containsExactly("a", "a1", "folded_a1").inOrder()
        val label = folded.last() as CommentThreadRow.Folded
        assertThat(label.count).isEqualTo(3)
        assertThat(label.lines).isEqualTo(CommentThreadLines(column = 3, elbowFrom = 2, through = emptySet()))

        val open = rows(tree, unfolded = setOf("a1"))
        assertThat(open.map { it.key })
            .containsExactly("a", "a1", "a11", "folded_a11", "a12")
            .inOrder()
        assertThat(open[2].lines).isEqualTo(CommentThreadLines(column = 3, elbowFrom = 2, through = setOf(2)))
    }

    @Test
    fun `indentation stops at the last column and deeper replies share their parent's line`() {
        val tree = node("a", node("b", node("c", node("d", node("e")))))

        val result = rows(tree, unfolded = setOf("b", "c", "d"))

        assertThat(result.map { it.key }).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(result.map { it.lines.column }).containsExactly(1, 2, 3, 3, 3).inOrder()
        val c = result[2].lines
        assertThat(c.elbowFrom).isEqualTo(2)
        assertThat(c.ownLine).isNull()
        assertThat(c.through).contains(2)
        assertThat(result[3].lines.elbowFrom).isEqualTo(2)
        assertThat(result[4].lines.elbowFrom).isEqualTo(2)
        assertThat(result[4].lines.through).isEmpty()
    }

    @Test
    fun `an ancestor's line passes every row of a branch that has a later sibling`() {
        val result = rows(node("a", node("a1"), node("a2")), node("b"))

        assertThat(result.map { it.key }).containsExactly("a", "a1", "a2", "b").inOrder()
        assertThat(result[1].lines.through).containsExactly(0, 1)
        assertThat(result[2].lines.through).containsExactly(0)
    }
}
