package io.github.aedev.flow.ui.screens.settings.about

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.screens.settings.index.AboutIndex
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex

private enum class AboutDialog { CHANGELOG, DEVICE, NOTICES }

private const val WEBSITE_URL = "https://flow-tube.org"
private const val GITHUB_URL = "https://github.com/Flow-Tube/Flow"
private const val REDDIT_URL = "https://www.reddit.com/r/Flow_Official/"
private const val CREATOR_URL = "https://github.com/A-EDev"
private const val NEWPIPE_URL = "https://github.com/TeamNewPipe/NewPipeExtractor"
private const val LICENSE_URL = "https://github.com/Flow-Tube/Flow/blob/main/License"
private const val CREATOR_AVATAR_URL = "https://github.com/A-EDev.png?size=144"

private val LogoSize = 72.dp
private val HeaderPadding = 24.dp
private val HeaderSpacing = 8.dp
private val AvatarSize = 48.dp

/** The app's version, where to find and reach the project, and its licences; on a TV, the remote's key guide. */
@Composable
internal fun AboutScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var dialog by rememberSaveable { mutableStateOf<AboutDialog?>(null) }
    val open = { url: String -> runCatching { uriHandler.openUri(url) } }

    SettingsPage(
        title = stringResource(R.string.settings_item_about_flow),
        onBack = onBack,
        highlight = highlight,
    ) {
        item("about.header") { AboutHeader() }
        group(key = "about.creator") {
            row(AboutIndex.creator.key) { shape -> CreatorRow(shape = shape, onClick = { open(CREATOR_URL) }) }
        }
        group(key = "about.app", header = R.string.section_app) {
            nav(AboutIndex.changelog, icon = Icons.Outlined.History, showChevron = false, onClick = { dialog = AboutDialog.CHANGELOG })
            nav(
                DestinationIndex.entry(SettingsDestination.REMOTE_GUIDE),
                icon = Icons.Outlined.SettingsRemote,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.REMOTE_GUIDE)) },
            )
        }
        group(key = "about.contact", header = R.string.section_contact) {
            nav(AboutIndex.website, icon = Icons.Outlined.Public, showChevron = false, onClick = { open(WEBSITE_URL) })
            nav(AboutIndex.github, iconRes = R.drawable.ic_github, showChevron = false, onClick = { open(GITHUB_URL) })
            nav(AboutIndex.reddit, icon = IconReddit, showChevron = false, onClick = { open(REDDIT_URL) })
        }
        group(key = "about.legal", header = R.string.section_legal) {
            nav(AboutIndex.license, icon = Icons.Outlined.Description, showChevron = false, onClick = { open(LICENSE_URL) })
            nav(AboutIndex.newPipe, icon = Icons.Outlined.Extension, showChevron = false, onClick = { open(NEWPIPE_URL) })
            nav(AboutIndex.notices, icon = Icons.Outlined.Gavel, showChevron = false, onClick = { dialog = AboutDialog.NOTICES })
        }
        group(key = "about.device", header = R.string.section_device) {
            nav(
                AboutIndex.deviceInfo,
                value = "${Build.MANUFACTURER} ${Build.MODEL}",
                icon = Icons.Outlined.Smartphone,
                showChevron = false,
                onClick = { dialog = AboutDialog.DEVICE },
            )
        }
    }

    when (dialog) {
        AboutDialog.CHANGELOG -> ChangelogSheet(onDismiss = { dialog = null })
        AboutDialog.DEVICE -> DeviceInfoDialog(onDismiss = { dialog = null })
        AboutDialog.NOTICES -> NoticesDialog(onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun AboutHeader() {
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)
    val version =
        remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                .getOrNull()
                ?.let { it.versionName to PackageInfoCompat.getLongVersionCode(it).toString() }
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = HeaderPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HeaderSpacing),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notification_logo),
            contentDescription = null,
            modifier = Modifier.size(LogoSize),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.v_version_template, version?.first ?: unknown, version?.second ?: "0"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CreatorRow(
    shape: Shape,
    onClick: () -> Unit,
) {
    var avatarLoaded by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onClick,
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            Box(
                modifier = Modifier.size(AvatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarLoaded) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AsyncImage(
                    model = CREATOR_AVATAR_URL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { avatarLoaded = true },
                    modifier = Modifier.matchParentSize(),
                )
            }
        },
        overlineContent = { Text(stringResource(R.string.about_creator)) },
        supportingContent = { Text(stringResource(R.string.settings_creator_role)) },
        trailingContent = { Icon(painterResource(R.drawable.ic_github), contentDescription = null) },
    ) {
        Text(stringResource(R.string.about_creator_name), style = MaterialTheme.typography.titleMedium)
    }
}
