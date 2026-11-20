package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Comment
import org.junit.Test

/**
 * Pins how a flat reply list becomes a conversation. YouTube stores one level of replies and marks
 * a reply-to-a-reply with nothing but the `@handle` it opens with.
 */
class CommentReplyTreeTest {
    private fun reply(
        id: String,
        author: String,
        text: String,
    ) = Comment(
        id = id,
        author = author,
        authorThumbnail = "",
        text = text,
        likeCount = 0,
        publishedTime = "1 day ago",
    )

    @Test
    fun `a reply that mentions an earlier reply hangs off it`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@DaniDipp", "Source code I compile myself"),
                    reply("r2", "@RachidBoudjelida", "SO basically we should throw everything"),
                    reply("r3", "@skbs981", "@RachidBoudjelida and also by the governments"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1", "r2").inOrder()
        assertThat(tree.last().children.map { it.comment.id }).containsExactly("r3")
    }

    @Test
    fun `a mention of someone who has not replied yet stays at the top level`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@first", "@nobody hello"),
                    reply("r2", "@second", "@alsoNobody hi"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1", "r2").inOrder()
        assertThat(tree.all { it.children.isEmpty() }).isTrue()
    }

    @Test
    fun `a reply mentioning its own author is not nested under itself`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@same", "first"),
                    reply("r2", "@same", "@same talking to myself"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1", "r2").inOrder()
    }

    @Test
    fun `nesting goes as deep as the conversation does`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@a", "start"),
                    reply("r2", "@b", "@a answering you"),
                    reply("r3", "@c", "@b answering you"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1")
        val second = tree.single().children.single()
        assertThat(second.comment.id).isEqualTo("r2")
        assertThat(
            second.children
                .single()
                .comment.id,
        ).isEqualTo("r3")
    }

    @Test
    fun `a mention only nests under someone who spoke earlier`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@a", "@b answering someone who has not replied yet"),
                    reply("r2", "@b", "here I am"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1", "r2").inOrder()
    }

    @Test
    fun `a mention behind a zero-width space still nests the reply`() {
        val tree =
            buildCommentReplyTree(
                listOf(
                    reply("r1", "@Kylek1986", "first"),
                    reply("r2", "@GuruOfTheMoon", "\u200B @Kylek1986 same"),
                ),
            )

        assertThat(tree.map { it.comment.id }).containsExactly("r1")
        assertThat(tree.single().children.map { it.comment.id }).containsExactly("r2")
    }

    @Test
    fun `a single reply needs no tree`() {
        val tree = buildCommentReplyTree(listOf(reply("r1", "@a", "@a hello")))

        assertThat(tree.single().comment.id).isEqualTo("r1")
        assertThat(tree.single().children).isEmpty()
    }
}
