package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.service.LinkPhase
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncScanContent
import kotlinx.coroutines.delay

private const val REQUEST_POLL_MS = 3_000L

/** Linked devices, below the one-time sync choices on the Sync screen. */
@Composable
internal fun LinkedDevicesSection(
    hostOnly: Boolean,
    viewModel: LinkedDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val leaveFailed by viewModel.leaveFailed.collectAsStateWithLifecycle()
    val consent by viewModel.consent.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    // Each finished sync may have changed the devices and when the server last heard from them.
    LaunchedEffect(state.linked, state.lastSync?.atMs) { if (state.linked) viewModel.refreshDevices() }
    // Devices asking to join with the admin token show up while this screen is open.
    LaunchedEffect(state.linked, state.storeUrl) {
        // Only a Flow server takes join requests.
        while (state.storeUrl?.let { it != LAN_STORE && !it.startsWith(WEBDAV_STORE) } == true) {
            viewModel.refreshRequests()
            delay(REQUEST_POLL_MS)
        }
    }

    HorizontalDivider()
    val words = state.words
    var scanning by rememberSaveable { mutableStateOf(false) }
    when {
        words != null -> {
            LinkedRecoveryWords(words, state.busy, viewModel::confirmWordsSaved)
        }

        link != LinkPhase.Idle -> {
            LinkedLinkPanel(link, viewModel::answer, viewModel::closeLink)
        }

        scanning -> {
            SyncScanContent(prompt = stringResource(R.string.linked_scan_new_device)) {
                scanning = false
                viewModel.enterCode(it)
            }
            SyncActionRow(confirmLabel = stringResource(R.string.linked_back), onConfirm = { scanning = false })
        }

        leaveFailed != null -> {
            LeaveAnyway(checkNotNull(leaveFailed), state.busy, viewModel::forget, viewModel::keepGroup)
        }

        state.linked -> {
            LinkedOverview(
                state = state,
                onSyncNow = viewModel::syncNow,
                onAddDevice = viewModel::addDevice,
                onScanNewDevice = if (hostOnly) null else ({ scanning = true }),
                onRename = viewModel::rename,
                onRemove = viewModel::remove,
                onLeave = viewModel::leave,
                onForget = viewModel::forget,
                onUpdateLogin = viewModel::updateLogin,
                consent = consent,
                onConsent = viewModel::setConsent,
                onAccept = viewModel::accept,
                onDecline = viewModel::decline,
            ) {
                LinkedProfilesCard(
                    choices = profiles,
                    busy = state.busy,
                    onLink = viewModel::linkProfile,
                    onCreateFor = viewModel::createProfileFor,
                    onStopHere = viewModel::stopProfileHere,
                    onStopEverywhere = viewModel::stopProfileEverywhere,
                )
            }
        }

        else -> {
            LinkedSetup(
                hostOnly = hostOnly,
                busy = state.busy,
                error = state.error,
                onCreate = viewModel::create,
                onResetAndCreate = viewModel::resetAndCreate,
                onCreateOnWebDav = viewModel::createOnWebDav,
                onCreateOnNetwork = viewModel::createOnNetwork,
                onEnterCode = viewModel::enterCode,
                onJoinWithAdmin = viewModel::joinWithAdmin,
                onShowCode = viewModel::showCode,
                onRestore = viewModel::restore,
                onRestoreFromWebDav = viewModel::restoreFromWebDav,
            )
        }
    }
}
