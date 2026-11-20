package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CommentThreadStateTest {
    private val saverScope = SaverScope { true }

    @Test
    fun `toggling replies opens and closes only that thread`() {
        val state = CommentThreadState()

        state.toggleReplies("a")
        assertThat(state.isRepliesOpen("a")).isTrue()
        assertThat(state.isRepliesOpen("b")).isFalse()

        state.toggleReplies("a")
        assertThat(state.isRepliesOpen("a")).isFalse()
    }

    @Test
    fun `expanding text stays expanded and is separate from replies`() {
        val state = CommentThreadState()

        state.expandText("a")
        state.expandText("a")

        assertThat(state.isTextExpanded("a")).isTrue()
        assertThat(state.isRepliesOpen("a")).isFalse()
    }

    @Test
    fun `clear closes every thread and collapses every text`() {
        val state = CommentThreadState(openReplies = listOf("a", "b"), expandedText = listOf("c"))

        state.clear()

        assertThat(state.isRepliesOpen("a")).isFalse()
        assertThat(state.isRepliesOpen("b")).isFalse()
        assertThat(state.isTextExpanded("c")).isFalse()
    }

    @Test
    fun `the saver round trips open threads and expanded text`() {
        val state =
            CommentThreadState().apply {
                toggleReplies("a")
                toggleReplies("b")
                expandText("c")
                unfold("d")
            }

        val saved = with(CommentThreadState.Saver) { saverScope.save(state) }
        val restored = CommentThreadState.Saver.restore(checkNotNull(saved))

        assertThat(restored).isNotNull()
        assertThat(restored!!.isRepliesOpen("a")).isTrue()
        assertThat(restored.isRepliesOpen("b")).isTrue()
        assertThat(restored.isTextExpanded("c")).isTrue()
        assertThat(restored.isTextExpanded("a")).isFalse()
        assertThat(restored.isUnfolded("d")).isTrue()
        assertThat(restored.isUnfolded("a")).isFalse()
    }

    @Test
    fun `an unfolded sub-thread stays open and clear folds it again`() {
        val state = CommentThreadState()

        state.unfold("r1")
        state.unfold("r1")
        assertThat(state.isUnfolded("r1")).isTrue()
        assertThat(state.isUnfolded("r2")).isFalse()

        state.clear()
        assertThat(state.isUnfolded("r1")).isFalse()
    }
}
