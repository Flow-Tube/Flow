package io.github.aedev.flow.ui.screens.settings.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.WorkHistory
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aedev.flow.ui.components.settings.SettingsDestination

/** One distinct icon per settings page, used on its row in the settings list. */
internal fun destinationIcon(destination: SettingsDestination): ImageVector =
    when (destination.root) {
        SettingsDestination.HOME -> Icons.Outlined.Settings
        SettingsDestination.TASTE -> Icons.Outlined.Psychology
        SettingsDestination.APPEARANCE -> Icons.Outlined.Palette
        SettingsDestination.LANGUAGE_REGION -> Icons.Outlined.Language
        SettingsDestination.PLAYBACK -> Icons.Outlined.PlayCircle
        SettingsDestination.QUALITY -> Icons.Outlined.HighQuality
        SettingsDestination.CONTENT -> Icons.Outlined.ViewAgenda
        SettingsDestination.TOPICS -> Icons.Outlined.FilterAlt
        SettingsDestination.INTEGRATIONS -> Icons.Outlined.Extension
        SettingsDestination.BACKUP -> Icons.Outlined.CloudSync
        SettingsDestination.SYNC -> Icons.Outlined.Devices
        SettingsDestination.HISTORY -> Icons.Outlined.History
        SettingsDestination.DOWNLOADS -> Icons.Outlined.Download
        SettingsDestination.NOTIFICATIONS -> Icons.Outlined.NotificationsNone
        SettingsDestination.NETWORK -> Icons.Outlined.Public
        SettingsDestination.WELLBEING -> Icons.Outlined.SelfImprovement
        SettingsDestination.SECURITY -> Icons.Outlined.Lock
        SettingsDestination.WIDGETS -> Icons.Outlined.Widgets
        SettingsDestination.ABOUT -> Icons.Outlined.Info
        SettingsDestination.DIAGNOSTICS -> Icons.Outlined.BugReport
        else -> Icons.Outlined.Settings
    }

internal object HomeRowIcons {
    val DeepFlow: ImageVector = Icons.Outlined.VisibilityOff
    val DeepFlowDuration: ImageVector = Icons.Outlined.Timer
    val DeepFlowHistory: ImageVector = Icons.Outlined.WorkHistory
    val DeepFlowScrobble: ImageVector = Icons.Outlined.CloudUpload
    val DeepFlowOnSearch: ImageVector = Icons.AutoMirrored.Outlined.ManageSearch
    val Updates: ImageVector = Icons.Outlined.Update
    val Support: ImageVector = Icons.Outlined.VolunteerActivism
}
