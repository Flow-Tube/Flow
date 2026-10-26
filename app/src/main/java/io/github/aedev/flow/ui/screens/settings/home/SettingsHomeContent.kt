package io.github.aedev.flow.ui.screens.settings.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.data.recommendation.FlowPersona
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.deepFlowStatus
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.HomeIndex

@Immutable
internal data class SettingsHomeState(
    val selected: SettingsDestination?,
    val persona: FlowPersona?,
    val deepFlow: DeepFlowState,
    /** Shown on the update row while a check the user started is running; null when idle. */
    val updateCheckingLabel: String?,
    /** Attached to the [selected] page's row, so a host can return focus to it. */
    val selectedRowFocus: FocusRequester? = null,
)

internal class SettingsHomeActions(
    val onOpen: (SettingsTarget) -> Unit,
    val onOpenDonations: () -> Unit,
    val onDeepFlowChange: (Boolean) -> Unit,
    val onDurationClick: () -> Unit,
    val onSaveHistoryChange: (Boolean) -> Unit,
    val onScrobbleChange: (Boolean) -> Unit,
    val onShowOnSearchChange: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,
)

/** The settings list when no search is running. */
internal fun SettingsListScope.homeContent(
    state: SettingsHomeState,
    actions: SettingsHomeActions,
) {
    item(HomeIndex.persona.key) {
        PersonaEntryCard(
            persona = state.persona,
            selected = state.selected == SettingsDestination.TASTE,
            onOpen = { actions.onOpen(SettingsTarget(SettingsDestination.TASTE)) },
            modifier = state.selectedFocusModifier(SettingsDestination.TASTE),
        )
    }

    fun SettingsGroupScope.page(destination: SettingsDestination) =
        nav(
            entry = DestinationIndex.entry(destination),
            onClick = { actions.onOpen(SettingsTarget(destination)) },
            icon = destinationIcon(destination),
            selected = state.selected == destination,
            showChevron = state.selected == null,
            modifier = state.selectedFocusModifier(destination),
        )

    // TV Home has no logo to long-press.
    val deepFlowHint = R.string.deep_flow_long_press_hint.takeUnless { onTv }
    group(key = "home.engine", header = R.string.settings_flow_engine_header, footer = deepFlowHint) {
        row(HomeIndex.deepFlow.key) { shape ->
            FlowSwitchRow(
                title = stringResource(HomeIndex.deepFlow.title),
                supportingText = deepFlowStatus(state.deepFlow) ?: stringResource(R.string.deep_flow_mode_subtitle),
                checked = state.deepFlow.active,
                onCheckedChange = actions.onDeepFlowChange,
                leadingIcon = HomeRowIcons.DeepFlow,
                shape = shape,
            )
        }
        row(HomeIndex.deepFlowDuration.key) { shape ->
            FlowNavRow(
                title = stringResource(HomeIndex.deepFlowDuration.title),
                supportingText = deepFlowDurationLabel(state.deepFlow.expireHours),
                onClick = actions.onDurationClick,
                leadingIcon = HomeRowIcons.DeepFlowDuration,
                shape = shape,
            )
        }
        switch(
            HomeIndex.deepFlowHistory,
            checked = state.deepFlow.saveToHistory,
            onCheckedChange = actions.onSaveHistoryChange,
            icon = HomeRowIcons.DeepFlowHistory,
        )
        switch(
            HomeIndex.deepFlowScrobble,
            checked = state.deepFlow.scrobble,
            onCheckedChange = actions.onScrobbleChange,
            icon = HomeRowIcons.DeepFlowScrobble,
        )
        switch(
            HomeIndex.deepFlowOnSearch,
            checked = state.deepFlow.showOnSearch,
            onCheckedChange = actions.onShowOnSearchChange,
            icon = HomeRowIcons.DeepFlowOnSearch,
        )
        page(SettingsDestination.TOPICS)
    }
    group(key = "home.look", header = R.string.settings_group_look) {
        page(SettingsDestination.APPEARANCE)
        page(SettingsDestination.LANGUAGE_REGION)
        page(SettingsDestination.WIDGETS)
    }
    group(key = "home.watching", header = R.string.settings_group_watching) {
        page(SettingsDestination.PLAYBACK)
        page(SettingsDestination.QUALITY)
        page(SettingsDestination.CONTENT)
        page(SettingsDestination.INTEGRATIONS)
    }
    group(key = "home.data", header = R.string.settings_group_data) {
        page(SettingsDestination.BACKUP)
        page(SettingsDestination.SYNC)
        page(SettingsDestination.HISTORY)
        page(SettingsDestination.DOWNLOADS)
    }
    group(key = "home.system", header = R.string.settings_group_system) {
        page(SettingsDestination.NOTIFICATIONS)
        page(SettingsDestination.NETWORK)
        page(SettingsDestination.WELLBEING)
    }
    group(key = "home.about", header = R.string.settings_header_about) {
        page(SettingsDestination.ABOUT)
        page(SettingsDestination.DIAGNOSTICS)
        if (BuildConfig.UPDATER_ENABLED) {
            nav(
                HomeIndex.checkForUpdates,
                onClick = actions.onCheckForUpdates,
                value = state.updateCheckingLabel,
                enabled = state.updateCheckingLabel == null,
                showChevron = false,
                icon = HomeRowIcons.Updates,
            )
        }
        nav(HomeIndex.support, onClick = actions.onOpenDonations, icon = HomeRowIcons.Support)
    }
}

private fun SettingsHomeState.selectedFocusModifier(destination: SettingsDestination): Modifier =
    selectedRowFocus?.takeIf { selected == destination }?.let { Modifier.focusRequester(it) } ?: Modifier
