package io.github.aedev.flow.ui.screens.sync

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.sync.SyncState
import io.github.aedev.flow.sync.protocol.SyncRole
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.topbar.FlowGlobalActionsMode
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.dpadFocusOnEnter
import io.github.aedev.flow.ui.screens.sync.linked.LinkedDevicesSection

/** Where the user is in the pre-session setup. Once a session starts, [SyncState] drives the UI. */
private enum class Step {
    CHOOSER,
    SEND_SELECT,
    SEND_TRANSPORT,
    SEND_SCAN,
    SEND_MANUAL,
    RECEIVE_TRANSPORT,
    RECEIVE_SCAN,
    RECEIVE_MANUAL,
}

/** The step to return to, or null when there is nothing left to back out of but the screen itself. */
private fun Step.previous(): Step? =
    when (this) {
        Step.CHOOSER -> null
        Step.SEND_SELECT -> Step.CHOOSER
        Step.SEND_TRANSPORT -> Step.SEND_SELECT
        Step.SEND_SCAN -> Step.SEND_TRANSPORT
        Step.SEND_MANUAL -> Step.SEND_TRANSPORT
        Step.RECEIVE_TRANSPORT -> Step.CHOOSER
        Step.RECEIVE_SCAN -> Step.RECEIVE_TRANSPORT
        Step.RECEIVE_MANUAL -> Step.RECEIVE_TRANSPORT
    }

@Composable
private fun Step.title(): String =
    when (this) {
        Step.CHOOSER -> stringResource(R.string.sync_devices_title)
        Step.SEND_SELECT -> stringResource(R.string.sync_choose_what_to_send)
        Step.SEND_TRANSPORT, Step.RECEIVE_TRANSPORT -> stringResource(R.string.sync_step_title_pair)
        Step.SEND_SCAN, Step.RECEIVE_SCAN -> stringResource(R.string.sync_step_title_scan)
        Step.SEND_MANUAL, Step.RECEIVE_MANUAL -> stringResource(R.string.sync_step_title_manual)
    }

/** Identifies the visible step for the cross-fade, without the volatile parts of the state. */
private fun SyncState.screenKey(step: Step): String =
    when (this) {
        is SyncState.Idle -> "idle:${step.name}"
        is SyncState.Preparing -> "preparing"
        is SyncState.Connecting -> "connecting"
        is SyncState.ShowingQr -> "qr"
        is SyncState.AwaitingSas -> "sas"
        is SyncState.AwaitingConsent -> "consent"
        is SyncState.Transferring -> "transferring"
        is SyncState.Done -> "done"
        is SyncState.Failed -> "failed"
    }

@Composable
private fun SyncState.title(step: Step): String =
    when (this) {
        is SyncState.Idle -> step.title()
        is SyncState.Preparing, is SyncState.Connecting -> stringResource(R.string.sync_devices_title)
        is SyncState.ShowingQr -> stringResource(R.string.sync_step_title_code)
        is SyncState.AwaitingSas -> stringResource(R.string.sync_step_title_verify)
        is SyncState.AwaitingConsent -> stringResource(R.string.sync_step_title_merge)
        is SyncState.Transferring -> stringResource(R.string.sync_step_title_syncing)
        is SyncState.Done -> stringResource(R.string.sync_step_title_syncing)
        is SyncState.Failed -> stringResource(R.string.sync_failed_title)
    }

/**
 * Device sync. [hostOnly] is for a device without a camera, such as a TV: it always shows its code for
 * the other device to scan, so sending and receiving skip the step that picks who scans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: (() -> Unit)?,
    hostOnly: Boolean = false,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val onNavigateBack = onBack ?: {}
    val syncProfileName by viewModel.syncProfileName.collectAsStateWithLifecycle()
    // One-time sync stays in Default; linked devices sync from any profile.
    val oneTimeElsewhere = syncProfileName.takeUnless { viewModel.syncsHere }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(Step.CHOOSER) }
    var selected by remember { mutableStateOf(COLLECTION_KEYS.toSet()) }

    fun restart() {
        viewModel.reset()
        step = Step.CHOOSER
    }

    // Back steps through the flow rather than abandoning it: from any live session it cancels back
    // to the chooser, and only the chooser itself leaves the screen.
    fun goBack() {
        when (state) {
            is SyncState.Idle -> {
                step.previous()?.let { step = it } ?: onNavigateBack()
            }

            is SyncState.Done -> {
                restart()
                onNavigateBack()
            }

            is SyncState.Failed -> {
                restart()
            }

            else -> {
                viewModel.cancel()
                step = Step.CHOOSER
            }
        }
    }

    val atStart = state is SyncState.Idle && step == Step.CHOOSER
    val screenKey = state.screenKey(step)
    val scrollState = rememberScrollState()
    // Each step opens at its top, not at the offset a long step such as the collection list left.
    LaunchedEffect(screenKey) { scrollState.scrollTo(0) }
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    BackHandler(enabled = !atStart || onBack != null, onBack = ::goBack)

    Scaffold(
        // The app shell's Scaffold already pads for WindowInsets.systemBars, so both this Scaffold
        // and the bar itself must consume nothing — otherwise the status-bar inset is applied twice
        // and leaves an empty band above the title.
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FlowTopBar(
                title = state.title(step),
                onBack = if (atStart && onBack == null) null else ::goBack,
                globalActions = FlowGlobalActionsMode.None,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .padding(bottom = LocalFlowBottomInsets.current.contentBottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Keyed on which step is showing, not on the state itself: `Transferring` changes on
            // every progress tick and would otherwise cross-fade the screen a few times a second.
            AnimatedContent(
                targetState = screenKey,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                label = "syncStep",
                contentAlignment = Alignment.TopCenter,
            ) { _ ->
                Column(
                    // Capped so the flow stays a readable column on tablets and unfolded devices
                    // instead of stretching every card the full width of the display.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .then(dpadFocusOnEnter()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SyncStepContent(
                        state = state,
                        step = step,
                        selected = selected,
                        hostOnly = hostOnly,
                        oneTimeElsewhere = oneTimeElsewhere,
                        onStepChange = { step = it },
                        onSelectedChange = { selected = it },
                        onHost = { role -> viewModel.host(role, selected.toList()) },
                        onJoin = { role, qr -> viewModel.join(role, qr, selected.toList()) },
                        onPrepareConnectionData = viewModel::extendPairingForManualShare,
                        onCancel = {
                            viewModel.cancel()
                            step = Step.CHOOSER
                        },
                        onConfirmSas = viewModel::confirmSas,
                        onConfirmConsent = viewModel::confirmConsent,
                        onFinish = {
                            restart()
                            onNavigateBack()
                        },
                        onRetry = ::restart,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStepContent(
    state: SyncState,
    step: Step,
    selected: Set<String>,
    hostOnly: Boolean,
    oneTimeElsewhere: String?,
    onStepChange: (Step) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    onHost: (SyncRole) -> Unit,
    onJoin: (SyncRole, String) -> Unit,
    onPrepareConnectionData: () -> String?,
    onCancel: () -> Unit,
    onConfirmSas: (Boolean) -> Unit,
    onConfirmConsent: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is SyncState.Idle -> {
            SyncSetupStep(
                step = step,
                selected = selected,
                hostOnly = hostOnly,
                oneTimeElsewhere = oneTimeElsewhere,
                onStepChange = onStepChange,
                onSelectedChange = onSelectedChange,
                onHost = onHost,
                onJoin = onJoin,
            )
        }

        is SyncState.Preparing -> {
            SyncBusyContent(stringResource(R.string.sync_preparing))
        }

        is SyncState.Connecting -> {
            SyncBusyContent(stringResource(R.string.sync_connecting))
        }

        is SyncState.ShowingQr -> {
            SyncQrContent(
                s = state,
                onPrepareConnectionData = onPrepareConnectionData,
                onCancel = onCancel,
            )
        }

        is SyncState.AwaitingSas -> {
            SyncSasContent(state.sas, onConfirm = onConfirmSas)
        }

        is SyncState.AwaitingConsent -> {
            SyncConsentContent(
                collections = state.summary.collections,
                onDecision = onConfirmConsent,
            )
        }

        is SyncState.Transferring -> {
            SyncTransferContent(state)
        }

        is SyncState.Done -> {
            SyncDoneContent(state, onDone = onFinish)
        }

        is SyncState.Failed -> {
            SyncFailedContent(state.message, onRetry = onRetry)
        }
    }
}

@Composable
private fun SyncSetupStep(
    step: Step,
    selected: Set<String>,
    hostOnly: Boolean,
    /** The profile one-time sync runs in, when it isn't this one. */
    oneTimeElsewhere: String?,
    onStepChange: (Step) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    onHost: (SyncRole) -> Unit,
    onJoin: (SyncRole, String) -> Unit,
) {
    when (step) {
        Step.CHOOSER -> {
            if (oneTimeElsewhere != null) {
                SyncInfoRow(icon = Icons.Outlined.SyncDisabled, text = stringResource(R.string.sync_default_only_title, oneTimeElsewhere))
            } else {
                SyncChooserContent(
                    onSend = { onStepChange(Step.SEND_SELECT) },
                    onReceive = { if (hostOnly) onHost(SyncRole.RECEIVER) else onStepChange(Step.RECEIVE_TRANSPORT) },
                )
            }
            LinkedDevicesSection(hostOnly)
        }

        Step.SEND_SELECT -> {
            SyncSelectContent(
                selected = selected,
                onSelectedChange = onSelectedChange,
                onContinue = { if (hostOnly) onHost(SyncRole.SENDER) else onStepChange(Step.SEND_TRANSPORT) },
            )
        }

        Step.SEND_TRANSPORT -> {
            SyncTransportContent(
                showQrLabel = stringResource(R.string.sync_show_qr_here),
                showQrHint = stringResource(R.string.sync_send_show_qr_hint),
                scanLabel = stringResource(R.string.sync_scan_other_qr),
                scanHint = stringResource(R.string.sync_send_scan_hint),
                onShowQr = { onHost(SyncRole.SENDER) },
                onScan = { onStepChange(Step.SEND_SCAN) },
                onManual = { onStepChange(Step.SEND_MANUAL) },
            )
        }

        Step.SEND_SCAN -> {
            SyncScanContent(
                prompt = stringResource(R.string.sync_scan_prompt_receive_code),
                onScanned = { onJoin(SyncRole.SENDER, it) },
            )
        }

        Step.SEND_MANUAL -> {
            SyncManualEntryContent(
                onSubmit = { onJoin(SyncRole.SENDER, it) },
            )
        }

        Step.RECEIVE_TRANSPORT -> {
            SyncTransportContent(
                showQrLabel = stringResource(R.string.sync_scan_other_qr),
                showQrHint = stringResource(R.string.sync_receive_scan_hint),
                scanLabel = stringResource(R.string.sync_show_qr_here),
                scanHint = stringResource(R.string.sync_receive_show_qr_hint),
                onShowQr = { onStepChange(Step.RECEIVE_SCAN) },
                onScan = { onHost(SyncRole.RECEIVER) },
                onManual = { onStepChange(Step.RECEIVE_MANUAL) },
            )
        }

        Step.RECEIVE_SCAN -> {
            SyncScanContent(
                prompt = stringResource(R.string.sync_scan_prompt_send_code),
                onScanned = { onJoin(SyncRole.RECEIVER, it) },
            )
        }

        Step.RECEIVE_MANUAL -> {
            SyncManualEntryContent(
                onSubmit = { onJoin(SyncRole.RECEIVER, it) },
            )
        }
    }
}
