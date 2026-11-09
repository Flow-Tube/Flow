package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Which comments have their replies open and their long text expanded, by comment id. */
@Stable
class CommentThreadState(
    openReplies: Collection<String> = emptyList(),
    expandedText: Collection<String> = emptyList(),
) {
    private val openReplies = mutableStateSetOf<String>().apply { addAll(openReplies) }
    private val expandedText = mutableStateSetOf<String>().apply { addAll(expandedText) }

    fun isRepliesOpen(commentId: String): Boolean = commentId in openReplies

    fun toggleReplies(commentId: String) {
        if (!openReplies.remove(commentId)) openReplies.add(commentId)
    }

    fun isTextExpanded(commentId: String): Boolean = commentId in expandedText

    fun expandText(commentId: String) {
        expandedText.add(commentId)
    }

    fun clear() {
        openReplies.clear()
        expandedText.clear()
    }

    companion object {
        val Saver: Saver<CommentThreadState, Any> =
            listSaver(
                save = { listOf(ArrayList(it.openReplies), ArrayList(it.expandedText)) },
                restore = { CommentThreadState(openReplies = it[0], expandedText = it[1]) },
            )
    }
}

@Composable
fun rememberCommentThreadState(vararg keys: Any?): CommentThreadState =
    rememberSaveable(*keys, saver = CommentThreadState.Saver) { CommentThreadState() }
