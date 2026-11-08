package io.github.aedev.flow.ui.screens.sync.linked

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Asking for some of the recovery words back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp")
class LinkedRecoveryWordsTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    @Test
    fun the_words_typed_back_are_not_kept_in_saved_state() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { LinkedRecoveryWords(List(24) { "word" }.joinToString(" "), busy = false, onSaved = {}) }
        rule.onNodeWithText(text(R.string.linked_words_saved)).performClick()
        val fields = rule.onAllNodes(hasSetTextAction())
        fields.fetchSemanticsNodes().indices.forEach { fields[it].performTextInput("word") }
        rule.onNodeWithText(text(R.string.linked_confirm)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("word").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.linked_confirm)).assertIsNotEnabled()
    }
}
