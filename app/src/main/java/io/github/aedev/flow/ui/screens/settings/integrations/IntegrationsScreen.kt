package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.IntegrationsIndex

/**
 * The outside services Flow can talk to: SponsorBlock, DeArrow, Return YouTube Dislike and, in
 * builds that include it, Discord Rich Presence. Each service's first row is its own switch, and
 * the rows that depend on it wait for it.
 */
@Composable
internal fun IntegrationsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: IntegrationsViewModel = hiltViewModel(),
) {
    val sponsorBlock by viewModel.sponsorBlock.collectAsStateWithLifecycle()
    val submitButton by viewModel.submitButton.collectAsStateWithLifecycle()
    val userId by viewModel.userId.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val skipButtonStyle by viewModel.skipButtonStyle.collectAsStateWithLifecycle()
    val deArrow by viewModel.deArrow.collectAsStateWithLifecycle()
    val discordState by viewModel.discordState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf<IntegrationsDialog?>(null) }
    var colourCategory by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.discordFailures.collect { snackbarHostState.showSnackbar(it) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_integrations_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        sponsorBlockSection(
            viewModel = viewModel,
            enabled = sponsorBlock,
            submitButton = submitButton,
            userId = userId,
            segments = segments,
            skipButtonStyle = skipButtonStyle,
            onPickSkipButtonStyle = { dialog = IntegrationsDialog.SKIP_BUTTON_STYLE },
            onEditUserId = { dialog = IntegrationsDialog.USER_ID },
            onPickColour = { colourCategory = it },
        )
        group(key = "integrations.scrobbling.group", header = R.string.scrobbling_title) {
            nav(
                DestinationIndex.entry(SettingsDestination.SCROBBLING),
                icon = Icons.Outlined.GraphicEq,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.SCROBBLING)) },
            )
        }
        group(key = "integrations.dearrow.group", header = R.string.player_settings_dearrow) {
            switch(IntegrationsIndex.deArrow, viewModel.deArrow, viewModel::setDeArrow, icon = Icons.Outlined.AutoFixHigh)
            switch(IntegrationsIndex.deArrowRandomFrame, viewModel.deArrowRandomFrame, viewModel::setDeArrowRandomFrame, enabled = deArrow)
            switch(IntegrationsIndex.deArrowBadge, viewModel.deArrowBadge, viewModel::setDeArrowBadge, enabled = deArrow)
        }
        group(key = "integrations.dislikes.group", header = R.string.player_settings_rytd_title) {
            switch(IntegrationsIndex.dislikes, viewModel.dislikes, viewModel::setDislikes, icon = Icons.Outlined.ThumbDownOffAlt)
        }
        if (BuildConfig.UPDATER_ENABLED) {
            discordSection(
                state = discordState,
                onEnabledChange = viewModel::setDiscordEnabled,
                onConnect = { dialog = IntegrationsDialog.DISCORD_RISK },
                onUnlink = viewModel::unlinkDiscord,
                onRetry = viewModel::retryDiscord,
            )
        }
    }

    IntegrationsDialogs(
        dialog = dialog,
        userId = userId,
        skipButtonStyle = skipButtonStyle,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
    colourCategory?.let { category ->
        SegmentColourDialog(
            category = category,
            current = segments[category]?.colorArgb,
            onSelect = { viewModel.setSegmentColor(category, it) },
            onDismiss = { colourCategory = null },
        )
    }
}
