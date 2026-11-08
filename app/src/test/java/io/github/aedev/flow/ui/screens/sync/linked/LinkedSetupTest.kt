package io.github.aedev.flow.ui.screens.sync.linked

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.linking.RestoreDevice
import io.github.aedev.flow.sync2.service.LinkedError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Choosing where a new group lives, and the WebDAV form that creates it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp")
class LinkedSetupTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    private val created = ArrayList<List<String>>()
    private val reset = ArrayList<List<String>>()

    private val kept = ArrayList<Set<String>>()

    private fun show(restoreDevices: List<RestoreDevice>? = null) = rule.setContent { Content(restoreDevices) }

    @Composable
    private fun Content(restoreDevices: List<RestoreDevice>? = null) {
        var error by remember { mutableStateOf<LinkedError?>(null) }
        LinkedSetup(
            hostOnly = false,
            busy = false,
            error = error,
            onCreate = { _, _ -> error = LinkedError.SERVER_HAS_GROUP },
            onResetAndCreate = { url, token -> reset += listOf(url, token) },
            onCreateOnWebDav = { url, user, password -> created += listOf(url, user, password) },
            onCreateOnNetwork = {},
            onEnterCode = { _, _ -> },
            onJoinWithAdmin = { _, _ -> },
            onShowCode = {},
            onRestore = { _, _ -> },
            onRestoreFromWebDav = { _, _, _, _ -> },
            restoreDevices = restoreDevices,
            onConfirmRestore = { kept += it },
            onCancelRestore = {},
        )
    }

    @Test
    fun an_admin_token_is_not_kept_in_saved_state_but_the_address_is() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Content() }
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_server)).performClick()
        rule.onNodeWithText(text(R.string.linked_server_url)).performTextInput("https://sync.example.org")
        rule.onNodeWithText(text(R.string.linked_admin_token)).performTextInput("admin")
        rule.onNodeWithText(text(R.string.linked_create)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("https://sync.example.org").assertExists()
        rule.onNodeWithText(text(R.string.linked_create)).assertIsNotEnabled()
    }

    @Test
    fun a_join_s_admin_token_is_not_kept_in_saved_state_but_the_address_is() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Content() }
        rule.onNodeWithText(text(R.string.linked_join_admin_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_server_url)).performTextInput("https://sync.example.org")
        rule.onNodeWithText(text(R.string.linked_admin_token)).performTextInput("admin")
        rule.onNodeWithText(text(R.string.linked_join_admin_ask)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("https://sync.example.org").assertExists()
        rule.onNodeWithText(text(R.string.linked_join_admin_ask)).assertIsNotEnabled()
    }

    @Test
    fun a_new_group_can_live_on_a_server_in_a_webdav_folder_or_on_this_network() {
        show()
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_server)).assertExists()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).assertExists()
        rule.onNodeWithText(text(R.string.linked_create_lan_title)).assertExists()
    }

    @Test
    fun the_webdav_form_creates_only_with_an_address_and_a_login() {
        show()
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).performClick()
        val create = rule.onNodeWithText(text(R.string.linked_create))
        create.assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.linked_webdav_url)).performTextInput(" https://cloud.example.org/dav/flow/ ")
        rule.onNodeWithText(text(R.string.linked_webdav_user)).performTextInput("me")
        create.assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.linked_webdav_password)).performTextInput("secret")
        create.assertIsEnabled().performClick()
        assertEquals(listOf(listOf("https://cloud.example.org/dav/flow/", "me", "secret")), created)
    }

    @Test
    fun a_group_is_restored_from_a_server_or_a_webdav_folder_but_not_a_network() {
        show()
        rule.onNodeWithText(text(R.string.linked_restore_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).assertExists()
        rule.onNodeWithText(text(R.string.linked_create_lan_title)).assertDoesNotExist()
    }

    @Test
    fun a_restore_keeps_no_device_unless_it_is_ticked() {
        val devices = listOf(RestoreDevice("aa".repeat(16), "Phone", "android"), RestoreDevice("bb".repeat(16), "", "android"))
        show(devices)
        rule.onNodeWithText(text(R.string.linked_restore_choose_body)).assertExists()
        rule.onNodeWithText(context.getString(R.string.linked_restore_unknown_device, "bbbbbbbb")).assertExists()
        rule.onNodeWithText(text(R.string.linked_restore)).performClick()
        assertEquals(listOf(emptySet<String>()), kept)
    }

    @Test
    fun a_ticked_device_is_kept() {
        show(listOf(RestoreDevice("aa".repeat(16), "Phone", "android")))
        rule.onAllNodes(isToggleable())[0].performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(text(R.string.linked_restore)).performClick()
        assertEquals(listOf(setOf("aa".repeat(16))), kept)
    }

    @Test
    fun a_server_that_already_has_a_group_offers_joining_or_a_confirmed_reset() {
        show()
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_server)).performClick()
        rule.onNodeWithText(text(R.string.linked_server_url)).performTextInput("https://sync.example.org")
        rule.onNodeWithText(text(R.string.linked_admin_token)).performTextInput("admin")
        rule.onNodeWithText(text(R.string.linked_create)).performClick()

        rule.onNodeWithText(text(R.string.linked_has_group_title)).assertExists()
        rule.onNodeWithText(text(R.string.linked_has_group_join)).assertExists()
        rule.onNodeWithText(text(R.string.linked_reset_server)).performClick()
        assertEquals("nothing is reset before the warning is confirmed", emptyList<List<String>>(), reset)
        rule.onNodeWithText(text(R.string.linked_reset_server_warning)).assertExists()
        rule.onNodeWithText(text(R.string.linked_reset_server_confirm)).performClick()
        assertEquals(listOf(listOf("https://sync.example.org", "admin")), reset)
    }

    @Test
    fun recovery_words_for_a_server_are_not_kept_in_saved_state_but_the_address_is() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Content() }
        rule.onNodeWithText(text(R.string.linked_restore_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_server)).performClick()
        rule.onNodeWithText(text(R.string.linked_server_url)).performTextInput("https://sync.example.org")
        rule.onNodeWithText(text(R.string.linked_recovery_words_field)).performTextInput("typed words")
        rule.onNodeWithText(text(R.string.linked_restore)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("https://sync.example.org").assertExists()
        rule.onNodeWithText("typed words").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.linked_restore)).assertIsNotEnabled()
    }

    @Test
    fun recovery_words_for_a_webdav_folder_are_not_kept_in_saved_state_but_the_address_is() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Content() }
        rule.onNodeWithText(text(R.string.linked_restore_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).performClick()
        rule.onNodeWithText(text(R.string.linked_webdav_url)).performTextInput("https://cloud.example.org/dav/flow/")
        rule.onNodeWithText(text(R.string.linked_webdav_user)).performTextInput("me")
        rule.onNodeWithText(text(R.string.linked_webdav_password)).performTextInput("secret")
        rule.onNodeWithText(text(R.string.linked_recovery_words_field)).performTextInput("typed words")
        rule.onNodeWithText(text(R.string.linked_restore)).assertIsEnabled()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("https://cloud.example.org/dav/flow/").assertExists()
        rule.onNodeWithText("typed words").assertDoesNotExist()
        rule.onNodeWithText(text(R.string.linked_webdav_password)).performTextInput("secret")
        rule.onNodeWithText(text(R.string.linked_restore)).assertIsNotEnabled()
    }
}
