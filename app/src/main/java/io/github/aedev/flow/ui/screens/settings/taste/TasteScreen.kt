package io.github.aedev.flow.ui.screens.settings.taste

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.backup.BackupOperation
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.deepFlowStatus
import io.github.aedev.flow.ui.utils.rememberDocumentPickerAvailable
import kotlinx.coroutines.launch
import java.time.LocalDate

private val LoadingHeight = 240.dp

private enum class ResetKind { VIDEO, MUSIC }

/** How Flow sees the viewer, with the levers that change it. */
@Composable
internal fun TasteScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    onOpenRecap: () -> Unit,
    viewModel: TasteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deepFlow by viewModel.deepFlow.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmReset by rememberSaveable { mutableStateOf<ResetKind?>(null) }
    val resetDone = stringResource(R.string.taste_reset_done)
    val busy = stringResource(R.string.backup_busy)
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(operation) {
        val message =
            when (val current = operation) {
                is BackupOperation.Succeeded -> current.message
                is BackupOperation.Failed -> current.message
                else -> return@LaunchedEffect
            }
        viewModel.dismissOperation()
        viewModel.reload()
        // Dismissing changes this effect's key, which would cancel a snackbar shown from here.
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    val start: (Uri?, (Uri) -> Boolean) -> Unit = { uri, action -> if (uri != null && !action(uri)) notice = busy }
    val exportVideo =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON)) { uri -> start(uri, viewModel::exportVideoProfile) }
    val exportMusic =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON)) { uri -> start(uri, viewModel::exportMusicProfile) }
    val importVideo =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> start(uri, viewModel::importVideoProfile) }
    val importMusic =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> start(uri, viewModel::importMusicProfile) }
    val hiddenLabel = hiddenCountLabel(state.hidden.count)
    val noQueriesLabel = stringResource(R.string.diagnostics_engine_queries_none)
    val startersLabel =
        state.engine
            ?.starters
            ?.takeIf { it.isNotEmpty() }
            ?.let { stringResource(R.string.taste_starter_searches_detail, it.joinToString(", ")) }
    val pausedLabel = stringResource(R.string.taste_learning_paused)
    val pausedStatus = deepFlowStatus(deepFlow)

    val canPickFile = rememberDocumentPickerAvailable()
    val actions =
        remember(viewModel, canPickFile) {
            TasteActions(
                onTopicPreferred = viewModel::setTopicPreferred,
                onBlockTopic = viewModel::blockTopic,
                onBlockChannel = viewModel::blockChannel,
                onForgetChannel = viewModel::forgetChannel,
                onClearChannelMemory = viewModel::clearChannelMemory,
                onOpenHidden = { onNavigate(SettingsTarget(SettingsDestination.HIDDEN_CONTENT)) },
                onOpenFavouriteArtists = { onNavigate(SettingsTarget(SettingsDestination.FAVOURITE_ARTISTS)) },
                onOpenDiscoverChannels = { onNavigate(SettingsTarget(SettingsDestination.DISCOVER_CHANNELS)) },
                onOpenRecap = onOpenRecap,
                onExportVideo = { exportVideo.launch("flow_video_profile_${LocalDate.now()}.json") }.takeIf { canPickFile },
                onImportVideo = { importVideo.launch(arrayOf(JSON)) }.takeIf { canPickFile },
                onResetVideo = { confirmReset = ResetKind.VIDEO },
                onExportMusic = { exportMusic.launch("flow_music_profile_${LocalDate.now()}.json") }.takeIf { canPickFile },
                onImportMusic = { importMusic.launch(arrayOf(JSON)) }.takeIf { canPickFile },
                onResetMusic = { confirmReset = ResetKind.MUSIC },
            )
        }

    SettingsPage(
        title = stringResource(R.string.taste_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        if (state.loading) {
            item("taste.loading") { FlowLoadingIndicator(Modifier.fillMaxWidth().height(LoadingHeight)) }
        } else {
            pausedStatus?.let { learningPaused(pausedLabel, it) }
            tasteContent(state, hiddenLabel, noQueriesLabel, startersLabel, actions)
        }
    }

    confirmReset?.let { kind ->
        val video = kind == ResetKind.VIDEO
        val title = if (video) R.string.reset_profile_dialog_title else R.string.taste_reset_music_title
        val body = if (video) R.string.taste_reset_video_body else R.string.taste_reset_music_body
        FlowAlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (video) viewModel.resetVideoProfile() else viewModel.resetMusicProfile()
                        confirmReset = null
                        notice = resetDone
                    },
                ) { Text(stringResource(R.string.reset_profile_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val JSON = "application/json"
