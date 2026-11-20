package io.github.aedev.flow.ui.components.shared

import io.github.aedev.flow.data.model.Comment

/** Indentation stops here so a deep conversation never squeezes the text off a phone screen. */
internal const val MAX_THREAD_COLUMN = 3

/** Replies down to this depth show up when a thread opens; deeper ones wait behind "N replies". */
internal const val OPEN_THREAD_DEPTH = 2

/**
 * The thread lines one row draws, by column. Column 0 is the top-level comment's avatar; column N
 * is the avatar of a reply indented N steps.
 *
 * @property column where the row's own avatar or label starts
 * @property elbowFrom the line that turns into this row
 * @property through lines that pass the row from top to bottom on their way to later rows
 * @property ownLine the line that drops from this row's avatar into its replies
 */
internal data class CommentThreadLines(
    val column: Int,
    val elbowFrom: Int,
    val through: Set<Int>,
    val ownLine: Int? = null,
)

internal sealed interface CommentThreadRow {
    val key: String
    val lines: CommentThreadLines

    data class Reply(
        val comment: Comment,
        val depth: Int,
        override val lines: CommentThreadLines,
    ) : CommentThreadRow {
        override val key: String get() = comment.id
    }

    /** The replies to [parentId] while they are folded away. */
    data class Folded(
        val parentId: String,
        val count: Int,
        override val lines: CommentThreadLines,
    ) : CommentThreadRow {
        override val key: String get() = "folded_$parentId"
    }

    /** The server has another page of replies. */
    data class MoreReplies(
        override val lines: CommentThreadLines,
    ) : CommentThreadRow {
        override val key: String get() = "more_replies"
    }
}

/**
 * Lays a reply tree out as the rows the thread draws, top to bottom, with the lines each one owns.
 *
 * A reply at [MAX_THREAD_COLUMN] or deeper stays in that column, and its replies hang off the same
 * line it hangs off, so the elbows keep turning in from the left however deep the talk goes.
 */
internal fun commentThreadRows(
    nodes: List<CommentReplyNode>,
    hasMore: Boolean,
    isOpen: (String) -> Boolean,
): List<CommentThreadRow> {
    val rows = mutableListOf<CommentThreadRow>()

    fun visit(
        siblings: List<CommentReplyNode>,
        depth: Int,
        inherited: Set<Int>,
        followedByMore: Boolean,
    ) {
        val parentLine = minOf(depth - 1, MAX_THREAD_COLUMN - 1)
        val childLine = minOf(depth, MAX_THREAD_COLUMN - 1)
        val column = minOf(depth, MAX_THREAD_COLUMN)
        siblings.forEachIndexed { index, node ->
            val last = index == siblings.lastIndex && !followedByMore
            val hasReplies = node.children.isNotEmpty()
            val sharesParentLine = hasReplies && childLine == parentLine
            rows +=
                CommentThreadRow.Reply(
                    comment = node.comment,
                    depth = depth,
                    lines =
                        CommentThreadLines(
                            column = column,
                            elbowFrom = parentLine,
                            through = if (!last || sharesParentLine) inherited + parentLine else inherited,
                            ownLine = column.takeIf { hasReplies && !sharesParentLine },
                        ),
                )
            if (!hasReplies) return@forEachIndexed
            val below = if (last) inherited else inherited + parentLine
            if (depth < OPEN_THREAD_DEPTH || isOpen(node.comment.id)) {
                visit(node.children, depth + 1, below, false)
            } else {
                rows +=
                    CommentThreadRow.Folded(
                        parentId = node.comment.id,
                        count = node.descendantCount(),
                        lines =
                            CommentThreadLines(
                                column = minOf(depth + 1, MAX_THREAD_COLUMN),
                                elbowFrom = childLine,
                                through = below,
                            ),
                    )
            }
        }
    }

    visit(nodes, depth = 1, inherited = emptySet(), followedByMore = hasMore)
    if (hasMore) {
        rows += CommentThreadRow.MoreReplies(CommentThreadLines(column = 1, elbowFrom = 0, through = emptySet()))
    }
    return rows
}

private fun CommentReplyNode.descendantCount(): Int = children.sumOf { 1 + it.descendantCount() }
