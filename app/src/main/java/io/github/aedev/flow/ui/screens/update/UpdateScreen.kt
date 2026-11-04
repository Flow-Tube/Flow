@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.screens.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.data.update.AppRelease
import io.github.aedev.flow.data.update.AppVersions
import io.github.aedev.flow.data.update.UpdateFailure
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowLogo
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val ContentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
private val HeroSize = 96.dp
private val HeroLogoWidth = 44.dp
private val HeaderSpacing = 12.dp
private val FactSpacing = 8.dp
private val NoticePadding = 16.dp

/**
 * A new release and the path to installing it. Downloads keep going when the page is closed, so
 * reopening it shows where the download got to.
 */
@Composable
fun UpdateScreen(
    onClose: () -> Unit,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val release = state.release

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            FlowTopBar(
                title = "",
                onBack = onClose,
                actions = {
                    if (release != null) {
                        UpdateMenu(
                            onSkip = {
                                viewModel.skip()
                                onClose()
                            },
                            onOpenGitHub = { uriHandler.openUri(release.pageUrl) },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (release != null) {
                UpdateActionBar(
                    stage = state.stage,
                    onPrimary = {
                        if (state.stage == UpdateStage.NeedsPermission) {
                            runCatching { context.startActivity(viewModel.installPermissionIntent()) }
                        } else {
                            viewModel.primaryAction()
                        }
                    },
                    onSecondary = { if (state.stage is UpdateStage.Downloading) viewModel.cancelDownload() else onClose() },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> {
                    FlowLoadingIndicator()
                }

                release == null -> {
                    FlowEmptyState(title = stringResource(R.string.flow_is_up_to_date), icon = Icons.Outlined.Verified)
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = FlowMaxContentWidth).fillMaxWidth(),
                        contentPadding = ContentPadding,
                    ) {
                        item(key = "header") { ReleaseHeader(release) }
                        item(key = "notice") { StageNotice(state.stage) }
                        state.notes?.let { releaseNotes(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseHeader(release: AppRelease) {
    val context = LocalContext.current
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Column(verticalArrangement = Arrangement.spacedBy(HeaderSpacing), modifier = Modifier.padding(bottom = HeaderSpacing)) {
        Surface(
            shape = MaterialShapes.SoftBurst.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(HeroSize),
        ) {
            Box(contentAlignment = Alignment.Center) { FlowLogo(Modifier.width(HeroLogoWidth)) }
        }
        Text(
            text = stringResource(R.string.update_title, release.version),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onBackground,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FactSpacing), verticalArrangement = Arrangement.spacedBy(FactSpacing)) {
            ReleaseFact(
                Icons.Outlined.SyncAlt,
                stringResource(
                    R.string.update_version_change,
                    AppVersions.installedLabel(BuildConfig.VERSION_NAME, BuildConfig.NIGHTLY_RUN),
                    release.version,
                ),
            )
            release.apk?.sizeBytes?.takeIf { it > 0 }?.let {
                ReleaseFact(
                    Icons.Outlined.Download,
                    Formatter.formatShortFileSize(context, it),
                )
            }
            release.publishedAt?.let {
                ReleaseFact(Icons.Outlined.CalendarToday, it.atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter))
            }
        }
    }
}

@Composable
private fun StageNotice(stage: UpdateStage) {
    val (icon, text) =
        when (stage) {
            UpdateStage.NeedsPermission -> Icons.Outlined.Shield to R.string.update_permission_notice
            is UpdateStage.Failed -> Icons.Outlined.ErrorOutline to stage.reason.messageRes()
            else -> return
        }
    val failed = stage is UpdateStage.Failed
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = HeaderSpacing),
    ) {
        Row(Modifier.padding(NoticePadding), horizontalArrangement = Arrangement.spacedBy(HeaderSpacing)) {
            Icon(icon, contentDescription = null)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun UpdateFailure.messageRes(): Int =
    when (this) {
        UpdateFailure.NETWORK -> R.string.update_failed_network
        UpdateFailure.STORAGE -> R.string.update_failed_storage
        UpdateFailure.CHECKSUM -> R.string.update_failed_checksum
        UpdateFailure.PACKAGE -> R.string.update_failed_package
        UpdateFailure.INSTALL -> R.string.update_failed_install
    }

@Composable
private fun UpdateMenu(
    onSkip: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_skip_version)) },
                leadingIcon = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                onClick = {
                    open = false
                    onSkip()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_open_github)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    open = false
                    onOpenGitHub()
                },
            )
        }
    }
}
