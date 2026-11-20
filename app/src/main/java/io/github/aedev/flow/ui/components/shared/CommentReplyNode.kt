package io.github.aedev.flow.ui.components.shared

import io.github.aedev.flow.data.model.Comment

/** A reply and whatever replied to it, at the depth it sits in the conversation. */
data class CommentReplyNode(
    val comment: Comment,
    val children: List<CommentReplyNode> = emptyList(),
)

// A leading mention can sit behind a zero-width space, which \s does not match.
private val LEADING_MENTION = Regex("""^[\s\u200B\u200C\u200D\u2060\uFEFF]*@([^\s,:\u200B]+)""")

/**
 * Arranges a flat reply list into the conversation it actually is.
 *
 * YouTube stores one level of replies: a reply to a reply comes back in the same list, marked only
 * by the `@handle` it opens with. Reading that mention back is what lets the thread show who was
 * answering whom instead of a single column of equals.
 *
 * A mention only nests a reply under someone who already spoke *earlier* in the thread, so a
 * mention of the top-level author, of a stranger, or of someone further down leaves the reply where
 * it is rather than reordering the conversation.
 */
fun buildCommentReplyTree(replies: List<Comment>): List<CommentReplyNode> {
    if (replies.size < 2) return replies.map { CommentReplyNode(it) }

    val children = LinkedHashMap<String, MutableList<Comment>>()
    val roots = mutableListOf<Comment>()
    val seenAuthors = HashMap<String, String>()

    replies.forEach { reply ->
        val handle =
            reply.author
                .trim()
                .removePrefix("@")
                .lowercase()
        val mention =
            LEADING_MENTION
                .find(reply.text)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        // Answering yourself is a continuation, not a branch, so it stays where it is.
        val parentId = mention?.takeIf { it != handle }?.let { seenAuthors[it] }
        if (parentId != null) {
            children.getOrPut(parentId) { mutableListOf() } += reply
        } else {
            roots += reply
        }
        seenAuthors.putIfAbsent(handle, reply.id)
    }

    fun node(comment: Comment): CommentReplyNode =
        CommentReplyNode(
            comment = comment,
            children = children[comment.id].orEmpty().map(::node),
        )

    return roots.map(::node)
}
