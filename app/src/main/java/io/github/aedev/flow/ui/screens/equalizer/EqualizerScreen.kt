package io.github.aedev.flow.ui.screens.equalizer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.audio.eq.EqMode
import io.github.aedev.flow.data.audio.eq.EqState
import io.github.aedev.flow.ui.components.equalizer.EqBandEditor
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.topbar.FlowGlobalActionsMode
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBarMenuItem
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBarOverflow
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.rememberFlowPaneState
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import io.github.aedev.flow.utils.shareLink
import kotlinx.coroutines.launch

private val SheetContentPadding = 24.dp

/**
 * The equalizer page, reached from the players' audio settings and from Settings › Playback.
 * [inSettings] keeps it to one pane, since the Settings host already splits a wide window.
 */
@Composable
fun EqualizerScreen(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inSettings: Boolean = false,
    highlight: String? = null,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var selectedBand by rememberSaveable { mutableStateOf<Int?>(null) }
    var dialog by remember { mutableStateOf<EqDialog?>(null) }
    val hasSystemEqualizer = remember { viewModel.systemEqualizerIntent() != null }
    val undoLabel = stringResource(R.string.undo)
    val panes = rememberFlowPaneState()
    val twoPane = !inSettings && panes.showsSidePane

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFromFile) }
    val openSystemEqualizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val exportTitle = stringResource(R.string.eq_export)
    val share: (String?) -> Unit = { presetId -> shareLink(context, viewModel.exportText(presetId), exportTitle) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result =
                snackbar.showSnackbar(
                    message = context.getString(message.text),
                    actionLabel = if (message.undo != null) undoLabel else null,
                )
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }
    LaunchedEffect(state.mode, state.active.curve.bands.size) {
        if (state.mode != EqMode.PARAMETRIC || (selectedBand ?: 0) >= state.active.curve.bands.size) selectedBand = null
    }
    BackHandler(enabled = showPresets) { showPresets = false }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current)) },
        topBar = {
            if (showPresets) {
                FlowTopBar(
                    title = stringResource(R.string.eq_presets),
                    onBack = { showPresets = false },
                    globalActions = FlowGlobalActionsMode.None,
                )
            } else {
                FlowTopBar(
                    title = stringResource(R.string.equalizer),
                    onBack = onBack,
                    globalActions = FlowGlobalActionsMode.None,
                    actions = {
                        IconButton(onClick = viewModel::undo, enabled = canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = undoLabel)
                        }
                        CompareButton(onBypass = viewModel::setBypass)
                        FlowTopBarOverflow(
                            items =
                                listOf(
                                    FlowTopBarMenuItem(
                                        stringResource(R.string.eq_presets),
                                        { showPresets = true },
                                        icon = Icons.Outlined.LibraryMusic,
                                    ),
                                    FlowTopBarMenuItem(
                                        stringResource(R.string.eq_import_file),
                                        { openFile.launch(IMPORT_TYPES) },
                                        icon = Icons.Outlined.UploadFile,
                                    ),
                                    FlowTopBarMenuItem(
                                        stringResource(R.string.eq_paste_text),
                                        { dialog = EqDialog.Paste },
                                        icon = Icons.Outlined.ContentPaste,
                                    ),
                                    FlowTopBarMenuItem(exportTitle, { share(null) }, icon = Icons.Outlined.IosShare),
                                    FlowTopBarMenuItem(
                                        stringResource(R.string.reset),
                                        viewModel::resetCurve,
                                        icon = Icons.Outlined.RestartAlt,
                                    ),
                                ),
                        )
                    },
                )
            }
        },
    ) { padding ->
        if (showPresets) {
            EqualizerPresetsPage(
                state = state,
                viewModel = viewModel,
                onSelected = { showPresets = false },
                onImportFile = { openFile.launch(IMPORT_TYPES) },
                onPaste = { dialog = EqDialog.Paste },
                onShare = share,
                onDialog = { dialog = it },
                modifier = Modifier.padding(padding),
            )
        } else {
            EqualizerMainContent(
                state = state,
                viewModel = viewModel,
                selectedBand = selectedBand,
                onSelectBand = { selectedBand = it },
                panes = panes,
                twoPane = twoPane,
                onOpenPresets = { showPresets = true },
                onSaveAs = { dialog = EqDialog.SaveAs },
                highlight = highlight,
                onSystemEqualizer =
                    if (hasSystemEqualizer) {
                        { viewModel.systemEqualizerIntent()?.let(openSystemEqualizer::launch) }
                    } else {
                        null
                    },
                modifier = Modifier.padding(padding),
            )
        }
    }

    val band = selectedBand
    if (!showPresets && band != null && !twoPane) {
        BandSheet(
            index = band,
            state = state,
            viewModel = viewModel,
            onDismiss = { selectedBand = null },
        )
    }

    EqualizerDialogs(
        dialog = dialog,
        importPreview = importPreview,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandSheet(
    index: Int,
    state: EqState,
    viewModel: EqualizerViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        EqBandEditor(
            index = index,
            bands = state.active.curve.bands,
            bassBoost = state.bassBoost,
            onChange = { viewModel.updateBand(index, it) },
            onRemove = {
                onDismiss()
                viewModel.removeBand(index)
            },
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(start = SheetContentPadding, end = SheetContentPadding, bottom = SheetContentPadding),
        )
    }
}

/**
 * Press and hold to hear the unprocessed sound; release to return to the equalizer. A tap shows the
 * tooltip, so the button explains itself instead of seeming to do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareButton(onBypass: (Boolean) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.eq_compare)
    LaunchedEffect(pressed) { onBypass(pressed) }
    DisposableEffect(Unit) { onDispose { onBypass(false) } }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text = label) } },
        state = tooltip,
    ) {
        IconButton(onClick = { scope.launch { tooltip.show() } }, interactionSource = interactions) {
            Icon(Icons.Outlined.Compare, contentDescription = label)
        }
    }
}

private val IMPORT_TYPES = arrayOf("text/*", "application/octet-stream")
