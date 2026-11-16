package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Guards the rule that fixes the "settings and notifications disappear when Home is disabled" bug:
 * a top bar with no back affordance is a root destination and must offer both shell actions.
 */
class FlowTopBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setBar(
        unread: Int = 0,
        account: TopBarAccountSettings = TopBarAccountSettings(showProfile = true, settingsInProfileMenu = false),
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ProvideFlowGlobalActions(
                    unreadNotifications = MutableStateFlow(unread),
                    account = MutableStateFlow(account),
                    onOpenNotifications = {},
                    onOpenSettings = {},
                    profileAction = { withSettings ->
                        Box(Modifier.size(48.dp).semantics { contentDescription = if (withSettings) PROFILE_WITH_SETTINGS else PROFILE })
                    },
                    content = content,
                )
            }
        }
    }

    @Test
    fun rootDestinationShowsNotificationsAndSettings() {
        setBar { FlowTopBar(title = "Library") }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.notifications))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertIsDisplayed()
    }

    @Test
    fun rootDestinationShowsTheProfileAfterSettings() {
        setBar { FlowTopBar(title = "Library") }

        composeRule.onNodeWithContentDescription(PROFILE).assertIsDisplayed()
    }

    @Test
    fun crowdedBarFoldsSettingsIntoTheProfile() {
        setBar { FlowTopBar(title = "Subscriptions", crowded = true) }

        composeRule.onNodeWithContentDescription(PROFILE_WITH_SETTINGS).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertDoesNotExist()
    }

    @Test
    fun hiddenProfileKeepsSettingsEvenOnACrowdedBar() {
        setBar(account = TopBarAccountSettings(showProfile = false, settingsInProfileMenu = true)) {
            FlowTopBar(title = "Subscriptions", crowded = true)
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PROFILE).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PROFILE_WITH_SETTINGS).assertDoesNotExist()
    }

    @Test
    fun detailDestinationShowsNeitherShellAction() {
        setBar { FlowTopBar(title = "Buffer", onBack = {}) }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.notifications))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.btn_back))
            .assertIsDisplayed()
    }

    @Test
    fun brandedLeadingSlotStillCountsAsARootDestination() {
        setBar { FlowTopBar(title = "FLOW", leading = {}) }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertIsDisplayed()
    }

    @Test
    fun unreadCountRendersABadge() {
        setBar(unread = 3) { FlowTopBar(title = "Home") }

        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun searchBarNeverShowsShellActions() {
        setBar {
            FlowSearchTopBar(
                query = "",
                onQueryChange = {},
                onClose = {},
                placeholder = "Search settings",
                autoFocus = false,
            )
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.settings))
            .assertDoesNotExist()
    }

    private companion object {
        const val PROFILE = "profile"
        const val PROFILE_WITH_SETTINGS = "profile with settings"
    }
}
