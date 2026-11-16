package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * App-shell destinations that must stay reachable from every root screen.
 *
 * Before this existed, `notifications` had a single entry point — the Home top bar — so turning
 * Home off in navigation settings orphaned the screen entirely. Root tabs 3 (Subscriptions) and 4
 * (Library) can never be hidden, so rendering these actions on every backless destination
 * guarantees reachability for any navigation configuration. The profile avatar is here for the
 * same reason.
 *
 * [unreadNotifications] and [account] are flows rather than current values so that a change
 * invalidates only the actions that collect them, instead of the whole shell that provides them.
 * [account] is null until it is read, and [profileAction] draws the avatar, given whether its menu
 * holds Settings; the shell supplies it so app chrome needs no knowledge of profiles.
 */
@Immutable
data class FlowGlobalActions(
    val unreadNotifications: StateFlow<Int>,
    val account: StateFlow<TopBarAccountSettings?>,
    val onOpenNotifications: () -> Unit,
    val onOpenSettings: () -> Unit,
    val profileAction: @Composable (withSettings: Boolean) -> Unit,
)

/**
 * Static because the provided value is remembered for the lifetime of the shell and never changes;
 * reading it must never invalidate a screen.
 */
val LocalFlowGlobalActions = staticCompositionLocalOf<FlowGlobalActions?> { null }

/**
 * Publishes the shell's global actions to every [FlowTopBar] below it. Call once, around the
 * `NavHost`.
 */
@Composable
fun ProvideFlowGlobalActions(
    unreadNotifications: StateFlow<Int>,
    account: StateFlow<TopBarAccountSettings?>,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    profileAction: @Composable (withSettings: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val actions =
        remember(unreadNotifications, account, onOpenNotifications, onOpenSettings, profileAction) {
            FlowGlobalActions(
                unreadNotifications = unreadNotifications,
                account = account,
                onOpenNotifications = onOpenNotifications,
                onOpenSettings = onOpenSettings,
                profileAction = profileAction,
            )
        }
    CompositionLocalProvider(LocalFlowGlobalActions provides actions, content = content)
}
