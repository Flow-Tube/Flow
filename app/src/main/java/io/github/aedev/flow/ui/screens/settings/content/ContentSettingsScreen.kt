package io.github.aedev.flow.ui.screens.settings.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SharedLinkAction
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.screens.settings.index.ContentIndex
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex

/**
 * What shows up in the feeds. How it is laid out lives in Appearance, and what the players do in
 * Player & playback.
 */
@Composable
internal fun ContentSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: ContentSettingsViewModel = hiltViewModel(),
) {
    val shortsContent by viewModel.shortsContent.collectAsStateWithLifecycle()
    val channelShortsTab by viewModel.channelShortsTab.collectAsStateWithLifecycle()
    val hideWatchedHome by viewModel.hideWatchedHome.collectAsStateWithLifecycle()
    val hideWatchedSubs by viewModel.hideWatchedSubs.collectAsStateWithLifecycle()
    val hideWatchedShorts by viewModel.hideWatchedShorts.collectAsStateWithLifecycle()
    val removeWatchedWatchLater by viewModel.removeWatchedWatchLater.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val linkHandlingLabel = rememberLinkHandlingLabel()
    var showThresholdDialog by rememberSaveable { mutableStateOf(false) }
    var showSharedLinksDialog by rememberSaveable { mutableStateOf(false) }

    SettingsPage(
        title = stringResource(R.string.settings_content_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "content.home", header = R.string.settings_section_home) {
            switch(ContentIndex.homeFeed, viewModel.homeFeed, viewModel::setHomeFeed)
            switch(ContentIndex.refreshOnReselect, viewModel.refreshOnReselect, viewModel::setRefreshOnReselect)
            switch(ContentIndex.homeChips, viewModel.homeChips, viewModel::setHomeChips)
            switch(ContentIndex.homeNewVideosButton, viewModel.homeNewVideosButton, viewModel::setHomeNewVideosButton)
            switch(ContentIndex.continueWatching, viewModel.continueWatching, viewModel::setContinueWatching)
            switch(ContentIndex.homeShortsShelf, viewModel.homeShortsShelf, viewModel::setHomeShortsShelf, enabled = shortsContent)
            switch(ContentIndex.homeSubscriptions, viewModel.homeSubscriptions, viewModel::setHomeSubscriptions)
            switch(ContentIndex.hideWatchedHome, viewModel.hideWatchedHome, viewModel::setHideWatchedHome)
        }
        group(key = "content.music", header = R.string.screen_title_music) {
            nav(
                DestinationIndex.entry(SettingsDestination.MUSIC_HOME),
                onClick = { onNavigate(SettingsTarget(SettingsDestination.MUSIC_HOME)) },
            )
        }
        group(key = "content.library", header = R.string.library) {
            nav(
                DestinationIndex.entry(SettingsDestination.LIBRARY),
                onClick = { onNavigate(SettingsTarget(SettingsDestination.LIBRARY)) },
            )
        }
        group(key = "content.subscriptions", header = R.string.settings_section_subscriptions) {
            switch(ContentIndex.subsVideos, viewModel.subsVideos, viewModel::setSubsVideos)
            switch(ContentIndex.subsShorts, viewModel.subsShorts, viewModel::setSubsShorts, enabled = shortsContent)
            switch(ContentIndex.subsShortsShelf, viewModel.subsShortsShelf, viewModel::setSubsShortsShelf, enabled = shortsContent)
            switch(ContentIndex.subsLive, viewModel.subsLive, viewModel::setSubsLive)
            switch(ContentIndex.subsUpcoming, viewModel.subsUpcoming, viewModel::setSubsUpcoming)
            switch(ContentIndex.subsCollaborations, viewModel.subsCollaborations, viewModel::setSubsCollaborations)
            switch(ContentIndex.subsPostsChip, viewModel.subsPostsChip, viewModel::setSubsPostsChip)
            switch(ContentIndex.subsUngrouped, viewModel.subsUngrouped, viewModel::setSubsUngrouped)
            switch(ContentIndex.hideWatchedSubs, viewModel.hideWatchedSubs, viewModel::setHideWatchedSubs)
            switch(ContentIndex.hideUnplayableSubs, viewModel.hideUnplayableSubs, viewModel::setHideUnplayableSubs)
            switch(ContentIndex.subsRefreshOnStartup, viewModel.subsRefreshOnStartup, viewModel::setSubsRefreshOnStartup)
            switch(ContentIndex.subsCheckedCount, viewModel.subsCheckedCount, viewModel::setSubsCheckedCount)
        }
        group(key = "content.shorts", header = R.string.content_settings_header_shorts) {
            switch(ContentIndex.shortsContent, viewModel.shortsContent, viewModel::setShortsContent)
            row(ContentIndex.channelShortsTab.key) { shape ->
                FlowSwitchRow(
                    title = stringResource(ContentIndex.channelShortsTab.title),
                    supportingText = stringResource(R.string.content_settings_channel_shorts_tab_subtitle),
                    checked = channelShortsTab || shortsContent,
                    onCheckedChange = viewModel::setChannelShortsTab,
                    enabled = !shortsContent,
                    shape = shape,
                )
            }
            switch(ContentIndex.hideWatchedShorts, viewModel.hideWatchedShorts, viewModel::setHideWatchedShorts, enabled = shortsContent)
        }
        group(key = "content.watch_later", header = R.string.watch_later) {
            switch(ContentIndex.removeWatchedWatchLater, viewModel.removeWatchedWatchLater, viewModel::setRemoveWatchedWatchLater)
        }
        if (hideWatchedHome || hideWatchedSubs || hideWatchedShorts || removeWatchedWatchLater) {
            group(key = "content.watched", header = R.string.settings_section_watched) {
                choice(ContentIndex.watchedThreshold, onClick = { showThresholdDialog = true }) {
                    val threshold by viewModel.watchedThreshold.collectAsStateWithLifecycle()
                    watchedThresholdLabel(threshold)
                }
            }
        }
        group(key = "content.notes", header = R.string.content_settings_notes_title) {
            switch(ContentIndex.notes, viewModel.notes, viewModel::setNotes)
            switch(ContentIndex.channelNotes, viewModel.channelNotes, viewModel::setChannelNotes, enabled = notes)
            switch(ContentIndex.videoNotes, viewModel.videoNotes, viewModel::setVideoNotes, enabled = notes)
        }
        group(key = "content.sharing", header = R.string.settings_section_sharing) {
            switch(ContentIndex.shareWithoutText, viewModel.shareWithoutText, viewModel::setShareWithoutText)
        }
        group(key = "content.links", header = R.string.settings_section_links) {
            nav(ContentIndex.openLinks, onClick = { openLinkSettings(context) }, value = linkHandlingLabel)
            choice(ContentIndex.sharedLinks, onClick = { showSharedLinksDialog = true }) {
                val action by viewModel.sharedLinkAction.collectAsStateWithLifecycle()
                sharedLinkActionLabel(action)
            }
        }
    }

    if (showThresholdDialog) {
        val threshold by viewModel.watchedThreshold.collectAsStateWithLifecycle()
        FlowChoiceDialog(
            title = stringResource(R.string.content_settings_watched_threshold_title),
            description = stringResource(R.string.content_settings_watched_threshold_dialog_body),
            options =
                listOf(
                    WatchedThreshold.ALMOST_FINISHED,
                    WatchedThreshold.PERCENT_99,
                    WatchedThreshold.PERCENT_95,
                    WatchedThreshold.PERCENT_90,
                ).map { FlowChoice(it, watchedThresholdLabel(it)) },
            selected = threshold,
            onSelect = viewModel::setWatchedThreshold,
            onDismiss = { showThresholdDialog = false },
        )
    }

    if (showSharedLinksDialog) {
        val action by viewModel.sharedLinkAction.collectAsStateWithLifecycle()
        FlowChoiceDialog(
            title = stringResource(R.string.content_settings_shared_links_title),
            description = stringResource(R.string.content_settings_shared_links_dialog_body),
            options = SharedLinkAction.entries.map { FlowChoice(it, sharedLinkActionLabel(it)) },
            selected = action,
            onSelect = viewModel::setSharedLinkAction,
            onDismiss = { showSharedLinksDialog = false },
        )
    }
}

@Composable
private fun sharedLinkActionLabel(action: SharedLinkAction): String =
    when (action) {
        SharedLinkAction.PLAY -> stringResource(R.string.content_settings_shared_links_play)
        SharedLinkAction.ASK_WHILE_PLAYING -> stringResource(R.string.content_settings_shared_links_ask_while_playing)
        SharedLinkAction.ALWAYS_ASK -> stringResource(R.string.content_settings_shared_links_always_ask)
    }

@Composable
private fun watchedThresholdLabel(threshold: WatchedThreshold): String =
    when (threshold) {
        WatchedThreshold.PERCENT_90 -> stringResource(R.string.content_settings_watched_threshold_90)
        WatchedThreshold.PERCENT_95 -> stringResource(R.string.content_settings_watched_threshold_95)
        WatchedThreshold.PERCENT_99 -> stringResource(R.string.content_settings_watched_threshold_99)
        WatchedThreshold.ALMOST_FINISHED -> stringResource(R.string.content_settings_watched_threshold_almost)
    }
