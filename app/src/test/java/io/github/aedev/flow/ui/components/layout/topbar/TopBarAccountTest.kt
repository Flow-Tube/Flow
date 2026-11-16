package io.github.aedev.flow.ui.components.layout.topbar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TopBarAccountTest {
    private fun account(
        showProfile: Boolean,
        settingsInMenu: Boolean,
        crowded: Boolean = false,
    ) = topBarAccount(TopBarAccountSettings(showProfile, settingsInMenu), crowded)

    @Test
    fun `by default a bar shows the settings icon and the profile`() {
        assertThat(account(showProfile = true, settingsInMenu = false)).isEqualTo(TopBarAccount.PROFILE_AND_SETTINGS)
    }

    @Test
    fun `settings in the profile menu leaves the profile alone`() {
        assertThat(account(showProfile = true, settingsInMenu = true)).isEqualTo(TopBarAccount.PROFILE_WITH_SETTINGS)
    }

    @Test
    fun `a crowded bar folds settings into the profile by default`() {
        assertThat(account(showProfile = true, settingsInMenu = false, crowded = true)).isEqualTo(TopBarAccount.PROFILE_WITH_SETTINGS)
    }

    @Test
    fun `a hidden profile keeps the settings icon whatever the menu switch says`() {
        for (settingsInMenu in listOf(true, false)) {
            for (crowded in listOf(true, false)) {
                assertThat(account(showProfile = false, settingsInMenu = settingsInMenu, crowded = crowded))
                    .isEqualTo(TopBarAccount.SETTINGS_ONLY)
            }
        }
    }
}
