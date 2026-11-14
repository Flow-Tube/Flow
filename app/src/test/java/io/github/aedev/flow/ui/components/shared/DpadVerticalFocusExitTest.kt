package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DpadVerticalFocusExitTest {
    @Test
    fun upAndDownMoveFocus() {
        assertThat(dpadVerticalExitDirection(Key.DirectionUp)).isEqualTo(FocusDirection.Up)
        assertThat(dpadVerticalExitDirection(Key.DirectionDown)).isEqualTo(FocusDirection.Down)
    }

    @Test
    fun leftAndRightStayWithTheControl() {
        assertThat(dpadVerticalExitDirection(Key.DirectionLeft)).isNull()
        assertThat(dpadVerticalExitDirection(Key.DirectionRight)).isNull()
        assertThat(dpadVerticalExitDirection(Key.DirectionCenter)).isNull()
    }
}
