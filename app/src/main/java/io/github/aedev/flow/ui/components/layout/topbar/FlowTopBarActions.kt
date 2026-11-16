package io.github.aedev.flow.ui.components.layout.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R

/**
 * The Notifications, Settings and profile actions every root destination gets, Settings and profile
 * as [topBarAccount] decides for a [crowded] or roomy bar. Until the account switches are read only
 * Notifications shows, so a bar whose profile holds Settings never flashes the settings icon.
 *
 * Renders nothing when the shell has not provided [LocalFlowGlobalActions] — the case in previews
 * and in isolated Compose tests.
 */
@Composable
internal fun FlowGlobalActionsRow(crowded: Boolean) {
    val actions = LocalFlowGlobalActions.current ?: return
    val unreadCount by actions.unreadNotifications.collectAsStateWithLifecycle()
    val settings by actions.account.collectAsStateWithLifecycle()

    FlowNotificationsAction(
        unreadCount = unreadCount,
        onClick = actions.onOpenNotifications,
    )
    val account = settings?.let { topBarAccount(it, crowded) } ?: return
    if (account != TopBarAccount.PROFILE_WITH_SETTINGS) {
        IconButton(onClick = actions.onOpenSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
    }
    if (account != TopBarAccount.SETTINGS_ONLY) {
        actions.profileAction(account == TopBarAccount.PROFILE_WITH_SETTINGS)
    }
}

@Composable
private fun FlowNotificationsAction(
    unreadCount: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = stringResource(R.string.notifications),
            )
            if (unreadCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .offset(x = 6.dp, y = (-4).dp)
                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                            .size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            if (unreadCount > 9) {
                                stringResource(R.string.notification_badge_9_plus)
                            } else {
                                unreadCount.toString()
                            },
                        color = MaterialTheme.colorScheme.onPrimary,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 9.sp,
                            ),
                    )
                }
            }
        }
    }
}

/** One entry in a top bar overflow menu, drawn as a plain Material 3 `DropdownMenuItem`. */
@Immutable
data class FlowTopBarMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

/**
 * Collapses extra actions behind a single overflow button.
 *
 * A root destination has room for two screen-specific actions before the two global ones; anything
 * past that belongs here rather than competing with the title for width.
 */
@Composable
fun FlowTopBarOverflow(
    items: List<FlowTopBarMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.more_options),
) {
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = contentDescription,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    leadingIcon =
                        item.icon?.let { icon ->
                            { Icon(imageVector = icon, contentDescription = null) }
                        },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
