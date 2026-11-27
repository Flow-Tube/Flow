package io.github.aedev.flow.ui.screens.settings.scrobbling

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.scrobble.ScrobbleService
import io.github.aedev.flow.data.scrobble.ScrobbleSettings
import io.github.aedev.flow.data.scrobble.ScrobbleWebhook
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.settings.index.ScrobblingIndex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Last.fm, Libre.fm and ListenBrainz accounts, webhooks, the viewer's own Last.fm key, and what gets sent. */
@Composable
internal fun ScrobblingScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: ScrobblingViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastFmReady by viewModel.lastFmReady.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    var dialog by rememberSaveable { mutableStateOf<ScrobbleDialogKind?>(null) }
    var dialogService by rememberSaveable { mutableStateOf(ScrobbleService.LASTFM) }
    var dialogWebhookId by rememberSaveable { mutableStateOf<String?>(null) }
    val waiting = settings.pending.values.sum()
    val waitingLabel = pluralStringResource(R.plurals.scrobbling_waiting, waiting, waiting)

    fun open(
        kind: ScrobbleDialogKind,
        service: ScrobbleService,
    ) {
        viewModel.resetSignIn()
        dialogService = service
        dialog = kind
    }

    val labels =
        AccountLabels(
            signedIn = stringResource(R.string.scrobbling_signed_in),
            signedInAs = { stringResource(R.string.scrobbling_signed_in_as, it) },
            notSignedIn = stringResource(R.string.scrobbling_not_signed_in),
            needsKey = stringResource(R.string.scrobbling_needs_key),
        )

    SettingsPage(
        title = stringResource(R.string.scrobbling_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        ScrobbleService.entries.forEach { service ->
            group(key = "scrobbling.${service.name}", header = service.headerRes()) {
                account(service, settings, ready = service != ScrobbleService.LASTFM || lastFmReady, labels) { kind -> open(kind, service) }
                if (service in settings.accounts) {
                    nav(
                        ScrobblingIndex.import(service),
                        icon = Icons.Outlined.Download,
                        enabled = importing == null,
                        showChevron = false,
                        onClick = { viewModel.importTaste(service) },
                    )
                }
                if (service == ScrobbleService.LASTFM) {
                    switch(ScrobblingIndex.ownKey, settings.ownKeyEnabled, { viewModel.setOwnKeys(it, settings.ownKeys) })
                    if (settings.ownKeyEnabled) {
                        nav(
                            ScrobblingIndex.ownKeyValues,
                            value =
                                settings.ownKeys.apiKey
                                    .takeIf { it.isNotBlank() }
                                    ?.masked(),
                            icon = Icons.Outlined.Key,
                            onClick = { open(ScrobbleDialogKind.OWN_KEY, service) },
                        )
                    }
                }
            }
        }
        group(key = "scrobbling.webhooks", header = R.string.scrobbling_webhooks, footer = R.string.scrobbling_webhooks_footer) {
            settings.webhooks.forEach { webhook ->
                webhookRow(webhook) {
                    dialogWebhookId = webhook.id
                    dialog = ScrobbleDialogKind.WEBHOOK
                }
            }
            nav(
                ScrobblingIndex.addWebhook,
                icon = Icons.Outlined.Add,
                showChevron = false,
                onClick = {
                    dialogWebhookId = null
                    dialog = ScrobbleDialogKind.WEBHOOK
                },
            )
        }
        group(key = "scrobbling.options", header = R.string.scrobbling_options) {
            switch(ScrobblingIndex.nowPlaying, settings.nowPlaying, viewModel::setNowPlaying)
            switch(ScrobblingIndex.sendLikes, settings.sendLikes, viewModel::setSendLikes)
            switch(ScrobblingIndex.local, settings.scrobbleLocal, viewModel::setScrobbleLocal)
            if (waiting > 0) {
                nav(
                    ScrobblingIndex.sendNow,
                    value = waitingLabel,
                    icon = Icons.Outlined.CloudUpload,
                    showChevron = false,
                    onClick = viewModel::sendNow,
                )
            }
        }
    }

    when (dialog) {
        ScrobbleDialogKind.SIGN_IN -> {
            SignInDialog(
                service = dialogService,
                state = signIn,
                onSubmit = { user, secret -> viewModel.signIn(dialogService, user, secret) },
                onDismiss = { dialog = if (signIn == SignInState.Done) ScrobbleDialogKind.OFFER_IMPORT else null },
            )
        }

        ScrobbleDialogKind.SIGN_OUT -> {
            SignOutDialog(dialogService, onConfirm = { viewModel.signOut(dialogService) }, onDismiss = { dialog = null })
        }

        ScrobbleDialogKind.OFFER_IMPORT -> {
            ImportOfferDialog(dialogService, onImport = { viewModel.importTaste(dialogService) }, onDismiss = { dialog = null })
        }

        ScrobbleDialogKind.OWN_KEY -> {
            OwnKeyDialog(settings.ownKeys, onSave = { viewModel.setOwnKeys(true, it) }, onDismiss = { dialog = null })
        }

        ScrobbleDialogKind.WEBHOOK -> {
            val editing = dialogWebhookId?.let { id -> settings.webhooks.firstOrNull { it.id == id } }
            WebhookDialog(
                current = editing,
                onSave = { name, url -> viewModel.saveWebhook(dialogWebhookId, name, url) },
                onDelete = { editing?.let { viewModel.removeWebhook(it.id) } },
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

private class AccountLabels(
    val signedIn: String,
    val signedInAs: @Composable (String) -> String,
    val notSignedIn: String,
    val needsKey: String,
)

private fun SettingsGroupScope.account(
    service: ScrobbleService,
    settings: ScrobbleSettings,
    ready: Boolean,
    labels: AccountLabels,
    onOpen: (ScrobbleDialogKind) -> Unit,
) {
    val account = settings.accounts[service]
    row(ScrobblingIndex.account(service).key) { shape ->
        val value =
            when {
                account != null && account.userName.isNotBlank() -> labels.signedInAs(account.userName)
                account != null -> labels.signedIn
                !ready -> labels.needsKey
                else -> labels.notSignedIn
            }
        FlowNavRow(
            title = stringResource(ScrobblingIndex.account(service).title),
            supportingText = value,
            enabled = account != null || ready,
            leadingIcon = Icons.Outlined.Person,
            showChevron = false,
            shape = shape,
            onClick = { onOpen(if (account != null) ScrobbleDialogKind.SIGN_OUT else ScrobbleDialogKind.SIGN_IN) },
        )
    }
}

private fun SettingsGroupScope.webhookRow(
    webhook: ScrobbleWebhook,
    onClick: () -> Unit,
) {
    row("scrobbling.webhook.${webhook.id}") { shape ->
        val host = webhook.url.toHttpUrlOrNull()?.host ?: webhook.url
        FlowNavRow(
            title = webhook.name.ifBlank { host },
            supportingText = host,
            leadingIcon = Icons.Outlined.Webhook,
            showChevron = false,
            shape = shape,
            onClick = onClick,
        )
    }
}

private fun ScrobbleService.headerRes(): Int =
    when (this) {
        ScrobbleService.LASTFM -> R.string.scrobbling_lastfm
        ScrobbleService.LIBREFM -> R.string.scrobbling_librefm
        ScrobbleService.LISTENBRAINZ -> R.string.scrobbling_listenbrainz
    }

private fun String.masked(): String = if (length <= VISIBLE_KEY_CHARS) this else take(VISIBLE_KEY_CHARS) + "…"

private const val VISIBLE_KEY_CHARS = 6
