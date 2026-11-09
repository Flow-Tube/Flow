package io.github.aedev.flow.ui.screens.sync

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pasting another device's connection data by hand. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp")
class SyncManualEntryTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    @Test
    fun pasted_connection_data_is_not_kept_in_saved_state() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { SyncManualEntryContent(onSubmit = {}) }
        rule.onNodeWithText(text(R.string.sync_connection_data_label)).performTextInput("typed data")
        rule.onNodeWithText(text(R.string.sync_connect)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("typed data").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.sync_connect)).assertIsNotEnabled()
    }
}
