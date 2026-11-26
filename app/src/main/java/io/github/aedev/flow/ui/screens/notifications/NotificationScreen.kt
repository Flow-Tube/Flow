package io.github.aedev.flow.ui.screens.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.flowSegmentShape
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import kotlinx.coroutines.launch
import java.time.ZoneId

private val GroupPadding = 12.dp
private val ListBottomPadding = 32.dp

@Composable
fun NotificationScreen(
    onBackClick: () -> Unit,
    onNotificationClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NotificationViewModel = hiltViewModel(),
) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val newIds by viewModel.newIds.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberToday(zone)
    val sections = remember(notifications, newIds, today) { groupNotifications(notifications, newIds, today, zone) }
    val sectionRows = remember(sections) { sections.map { it to groupByChannel(it.bucket, it.items) } }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val removedMessage = stringResource(R.string.notifications_removed)
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.undo)
    val savedMessage = stringResource(R.string.toast_added_to_watch_later)
    val alreadySavedMessage = stringResource(R.string.notifications_already_in_watch_later)
    var menu by remember { mutableStateOf<NotificationMenu?>(null) }

    LaunchedEffect(Unit) { viewModel.openInbox() }

    fun remove(items: List<NotificationEntity>) {
        if (!viewModel.deleteNotifications(items)) return
        val message =
            if (items.size ==
                1
            ) {
                removedMessage
            } else {
                resources.getQuantityString(R.plurals.notifications_removed_count, items.size, items.size)
            }
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(message, undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
        }
    }

    fun save(item: NotificationEntity) {
        viewModel.saveToWatchLater(item) { saved ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                if (!saved) {
                    snackbarHostState.showSnackbar(alreadySavedMessage, duration = SnackbarDuration.Short)
                    return@launch
                }
                val result = snackbarHostState.showSnackbar(savedMessage, undoLabel, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) viewModel.undoSave()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current))
        },
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.notifications),
                onBack = onBackClick,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.notifications_open_settings))
                    }
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_all_notifications))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (sections.isEmpty()) {
            EmptyInbox(onOpenSettings, Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = FlowMaxContentWidth).fillMaxWidth(),
                contentPadding =
                    PaddingValues(
                        start = GroupPadding,
                        end = GroupPadding,
                        bottom = flowBottomContentPadding(ListBottomPadding),
                    ),
            ) {
                sectionRows.forEach { (section, rows) ->
                    item(key = section.bucket, contentType = "header") {
                        FlowSectionHeader(stringResource(section.bucket.titleRes))
                    }
                    val isNew = section.bucket == NotificationBucket.NEW
                    val lines = inboxLines(rows, expanded.toSet())
                    itemsIndexed(
                        items = lines,
                        key = { _, line -> line.key },
                        contentType = { _, line -> line is InboxLine.Header },
                    ) { index, line ->
                        val shape = flowSegmentShape(index, lines.size)
                        val rowModifier = Modifier.animateItem().padding(bottom = FlowSegmentedGap)
                        when (line) {
                            is InboxLine.Item -> {
                                val item = line.notification
                                NotificationRow(
                                    notification = item,
                                    isNew = isNew,
                                    time = notificationTime(item.timestamp, today, zone).label(),
                                    shape = shape,
                                    onClick = { onNotificationClick(item.videoId) },
                                    onLongClick = { scope.launch { menu = NotificationMenu(item, viewModel.videoFor(item)) } },
                                    onDismiss = { remove(listOf(item)) },
                                    onSave = { save(item) },
                                    modifier = rowModifier,
                                )
                            }

                            is InboxLine.Header -> {
                                val newest = line.row.notifications.first()
                                NotificationChannelRow(
                                    row = line.row,
                                    isNew = isNew,
                                    time = notificationTime(newest.timestamp, today, zone).label(),
                                    expanded = line.expanded,
                                    shape = shape,
                                    onToggle = { expanded = if (line.expanded) expanded - line.key else expanded + line.key },
                                    onDismiss = { remove(line.row.notifications) },
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    menu?.let { open ->
        VideoQuickActionsBottomSheet(
            video = open.video,
            onDismiss = { menu = null },
            onRemoveFromCollection = { remove(listOf(open.notification)) },
            removeFromCollectionLabel = stringResource(R.string.delete),
            removeFromCollectionIcon = Icons.Outlined.Delete,
        )
    }

    if (confirmClear) {
        ClearAllDialog(
            count = notifications.size,
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyInbox(
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    FlowEmptyState(
        title = stringResource(R.string.peace_and_quiet),
        subtitle = stringResource(R.string.notifications_empty_body),
        icon = Icons.Outlined.NotificationsNone,
        modifier = modifier,
        action = {
            FilledTonalButton(onClick = onOpenSettings, shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.notifications_open_settings))
            }
        },
    )
}

@Composable
private fun ClearAllDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
        title = { Text(stringResource(R.string.notifications_clear_title)) },
        text = { Text(pluralStringResource(R.plurals.notifications_clear_body, count, count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.notifications_clear_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private class NotificationMenu(
    val notification: NotificationEntity,
    val video: Video,
)
