package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.runtime.Immutable

/** The two account switches as stored. */
@Immutable
data class TopBarAccountSettings(
    val showProfile: Boolean,
    val settingsInProfileMenu: Boolean,
)

/** Which account buttons a root top bar ends with. */
enum class TopBarAccount {
    /** The settings icon, then the profile avatar. */
    PROFILE_AND_SETTINGS,

    /** The profile avatar alone; its menu opens Settings. */
    PROFILE_WITH_SETTINGS,

    /** The settings icon alone. */
    SETTINGS_ONLY,
}

/**
 * A hidden profile always leaves the settings icon, so no combination loses Settings. A [crowded] bar, one with two
 * screen actions of its own, folds Settings into the profile so its title keeps its width.
 */
fun topBarAccount(
    settings: TopBarAccountSettings,
    crowded: Boolean,
): TopBarAccount =
    when {
        !settings.showProfile -> TopBarAccount.SETTINGS_ONLY
        settings.settingsInProfileMenu || crowded -> TopBarAccount.PROFILE_WITH_SETTINGS
        else -> TopBarAccount.PROFILE_AND_SETTINGS
    }
