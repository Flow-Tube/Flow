package io.github.aedev.flow.ui.components.shared.quickactions

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.ui.components.shared.FlowSheetPresenter
import io.github.aedev.flow.ui.components.shared.LocalFlowSheetPresenter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The remote's OK in a menu that a long press of OK opened. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class QuickActionsSheetKeyTest {
    @get:Rule
    val rule = createComposeRule()

    private var clicks = 0

    /** The sheet in the test's own window, where keys and focus work as they do in a dialog's. */
    private object InPlace : FlowSheetPresenter {
        @Composable
        override fun Present(
            onDismiss: () -> Unit,
            onBack: (() -> Unit)?,
            content: @Composable ColumnScope.() -> Unit,
        ) = Column(content = content)
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalFlowSheetPresenter provides InPlace) {
                QuickActionsSheet(onDismiss = {}) {
                    val inputMode = LocalInputModeManager.current
                    SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
                    Text("Item", Modifier.clickable { clicks++ })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Item").requestFocus().assertIsFocused()
    }

    private fun ok(
        action: Int,
        repeat: Int = 0,
    ) {
        val event =
            androidx.compose.ui.input.key
                .KeyEvent(KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat))
        rule.onNodeWithText("Item").performKeyPress(event)
        rule.waitForIdle()
    }

    @Test
    fun an_ok_still_held_from_opening_the_menu_selects_nothing() {
        show()
        ok(KeyEvent.ACTION_DOWN, repeat = 20)
        ok(KeyEvent.ACTION_DOWN, repeat = 21)
        ok(KeyEvent.ACTION_UP)
        assertEquals(0, clicks)
    }

    @Test
    fun the_next_press_of_ok_selects() {
        show()
        ok(KeyEvent.ACTION_DOWN, repeat = 20)
        ok(KeyEvent.ACTION_UP)
        ok(KeyEvent.ACTION_DOWN)
        ok(KeyEvent.ACTION_UP)
        assertEquals(1, clicks)
    }

    @Test
    fun a_press_that_starts_in_the_menu_selects_even_when_held() {
        show()
        ok(KeyEvent.ACTION_DOWN)
        ok(KeyEvent.ACTION_DOWN, repeat = 1)
        ok(KeyEvent.ACTION_UP)
        assertEquals(1, clicks)
    }
}
