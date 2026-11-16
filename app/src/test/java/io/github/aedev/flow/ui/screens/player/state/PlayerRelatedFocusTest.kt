package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test

class PlayerRelatedFocusTest {
    private val scope = CoroutineScope(StandardTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `tab does nothing until a list with cards binds`() {
        val focus = PlayerRelatedFocus()
        assertThat(focus.focusFirstCard(scope)).isFalse()
        val unbind = focus.bind {}
        assertThat(focus.focusFirstCard(scope)).isTrue()
        unbind()
        assertThat(focus.focusFirstCard(scope)).isFalse()
    }

    @Test
    fun `an old list unbinding keeps the newer one`() {
        val focus = PlayerRelatedFocus()
        val unbindOld = focus.bind {}
        focus.bind {}
        unbindOld()
        assertThat(focus.focusFirstCard(scope)).isTrue()
    }
}
