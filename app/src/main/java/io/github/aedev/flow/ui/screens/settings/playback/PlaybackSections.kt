package io.github.aedev.flow.ui.screens.settings.playback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerRelatedCardStyle
import io.github.aedev.flow.data.local.SpeedSwipeSide
import io.github.aedev.flow.ui.components.equalizer.equalizerSummary
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.SettingsToggleGroupRow
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.PlaybackGesturesIndex
import io.github.aedev.flow.ui.screens.settings.index.PlaybackIndex

private const val UNLIMITED_TITLE_LINES = 0

internal fun SettingsListScope.playbackSections(
    viewModel: PlaybackSettingsViewModel,
    state: PlaybackStructure,
    openDialog: (PlaybackDialog) -> Unit,
    onNavigate: (SettingsTarget) -> Unit,
    onAllowBackground: () -> Unit,
) {
    group(key = "playback.general", header = R.string.playback_header) {
        switch(PlaybackIndex.backgroundPlay, viewModel.backgroundPlay, viewModel::setBackgroundPlay)
        if (state.backgroundRestricted) {
            row("playback.background_restricted") { shape ->
                FlowNavRow(
                    title = stringResource(R.string.playback_background_restricted_title),
                    supportingText = stringResource(R.string.playback_background_restricted_subtitle),
                    leadingIcon = Icons.Outlined.BatteryAlert,
                    showChevron = false,
                    shape = shape,
                    onClick = onAllowBackground,
                )
            }
        }
        switch(PlaybackIndex.startPaused, viewModel.startPaused, viewModel::setStartPaused)
        switch(PlaybackIndex.openFullscreen, viewModel.openFullscreen, viewModel::setOpenFullscreen)
        switch(PlaybackIndex.tiltFullscreen, viewModel.tiltFullscreen, viewModel::setTiltFullscreen)
        switch(PlaybackIndex.autoplay, viewModel.autoplay, viewModel::setAutoplay, enabled = !state.loopAll)
        switch(PlaybackIndex.queueAutoplay, viewModel.queueAutoplay, viewModel::setQueueAutoplay, enabled = !state.loopAll)
        choice(PlaybackIndex.autoplayCountdown, onClick = { openDialog(PlaybackDialog.AUTOPLAY_COUNTDOWN) }) {
            val seconds by viewModel.autoplayCountdown.collectAsStateWithLifecycle()
            countdownLabel(seconds)
        }
        switch(PlaybackIndex.loopAll, viewModel.loopAll, viewModel::setLoopAll)
        switch(PlaybackIndex.skipSilence, viewModel.skipSilence, viewModel::setSkipSilence)
        switch(PlaybackIndex.playDuringCalls, viewModel.playDuringCalls, viewModel::setPlayDuringCalls)
    }
    group(key = "playback.sound", header = R.string.eq_settings_section) {
        nav(
            DestinationIndex.entry(SettingsDestination.EQUALIZER),
            onClick = { onNavigate(SettingsTarget(SettingsDestination.EQUALIZER)) },
            valueText = { equalizerSummary() },
        )
        switch(PlaybackIndex.normalizeVolume, viewModel.normalizeVolume, viewModel::setNormalizeVolume)
    }
    group(key = "playback.speed", header = R.string.settings_section_speed) {
        switch(PlaybackIndex.rememberSpeed, viewModel.rememberSpeed, viewModel::setRememberSpeed)
        switch(PlaybackIndex.musicNormalSpeed, viewModel.musicNormalSpeed, viewModel::setMusicNormalSpeed)
        switch(PlaybackIndex.speedPerChannel, viewModel.speedPerChannel, viewModel::setSpeedPerChannel)
        switch(PlaybackIndex.customSpeeds, viewModel.customSpeeds, viewModel::setCustomSpeeds)
        if (state.customSpeeds) {
            row(PlaybackIndex.speedPresets.key) { shape -> SpeedPresetEditor(viewModel = viewModel, shape = shape) }
        }
        switch(PlaybackIndex.speedSlider, viewModel.speedSlider, viewModel::setSpeedSlider)
        choice(PlaybackIndex.longPressSpeed, onClick = { openDialog(PlaybackDialog.LONG_PRESS_SPEED) }) {
            val speed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
            longPressSpeedLabel(speed)
        }
    }
    group(key = "playback.gestures", header = R.string.player_appearance_gestures_header) {
        choice(PlaybackGesturesIndex.doubleTapSeek, onClick = { openDialog(PlaybackDialog.DOUBLE_TAP_SEEK) }) {
            val seconds by viewModel.doubleTapSeek.collectAsStateWithLifecycle()
            if (seconds <= 0) {
                stringResource(R.string.player_settings_double_tap_seek_off)
            } else {
                pluralStringResource(R.plurals.player_settings_double_tap_seek_subtitle, seconds, seconds)
            }
        }
        if (state.doubleTapSeek) {
            choice(PlaybackGesturesIndex.seekZoneWidth, onClick = { openDialog(PlaybackDialog.SEEK_ZONE_WIDTH) }) {
                val zone by viewModel.seekZone.collectAsStateWithLifecycle()
                stringResource(zone.labelRes())
            }
        }
        switch(
            PlaybackGesturesIndex.brightnessGesture,
            viewModel.brightnessGesture,
            viewModel::setBrightnessGesture,
            enabled = state.speedSwipeSide != SpeedSwipeSide.LEFT,
        )
        switch(PlaybackGesturesIndex.rememberBrightness, viewModel.rememberBrightness, viewModel::setRememberBrightness)
        switch(
            PlaybackGesturesIndex.volumeGesture,
            viewModel.volumeGesture,
            viewModel::setVolumeGesture,
            enabled = state.speedSwipeSide != SpeedSwipeSide.RIGHT,
        )
        switch(PlaybackGesturesIndex.volumeBoost, viewModel.volumeBoost, viewModel::setVolumeBoost)
        choice(PlaybackGesturesIndex.speedSwipe, onClick = { openDialog(PlaybackDialog.SPEED_SWIPE_SIDE) }) {
            speedSwipeSideLabel(state.speedSwipeSide)
        }
        if (state.speedSwipeSide != SpeedSwipeSide.OFF || state.musicSideSwipes) {
            choice(PlaybackGesturesIndex.speedSwipeStep, onClick = { openDialog(PlaybackDialog.SPEED_SWIPE_STEP) }) {
                val step by viewModel.speedSwipeStep.collectAsStateWithLifecycle()
                speedSwipeStepLabel(step)
            }
        }
        switch(PlaybackGesturesIndex.seekGesture, viewModel.seekGesture, viewModel::setSeekGesture)
        switch(PlaybackGesturesIndex.haptics, viewModel.haptics, viewModel::setHaptics)
        switch(PlaybackGesturesIndex.controlsWhileLoading, viewModel.controlsWhileLoading, viewModel::setControlsWhileLoading)
    }
    group(key = "playback.buttons", header = R.string.settings_section_player_buttons) {
        switch(PlaybackIndex.castButton, viewModel.castButton, viewModel::setCastButton)
        switch(PlaybackIndex.captionsButton, viewModel.captionsButton, viewModel::setCaptionsButton)
        switch(PlaybackIndex.pipButton, viewModel.pipButton, viewModel::setPipButton)
        switch(PlaybackIndex.autoplayButton, viewModel.autoplayButton, viewModel::setAutoplayButton)
        switch(PlaybackIndex.sleepTimerButton, viewModel.sleepTimerButton, viewModel::setSleepTimerButton)
        switch(PlaybackIndex.lockButton, viewModel.lockButton, viewModel::setLockButton)
        switch(PlaybackIndex.speedIndicator, viewModel.speedIndicator, viewModel::setSpeedIndicator)
        switch(PlaybackIndex.commentsButton, viewModel.commentsButton, viewModel::setCommentsButton)
        switch(PlaybackIndex.sponsorBlockButton, viewModel.sponsorBlockButton, viewModel::setSponsorBlockButton)
        switch(PlaybackIndex.nextPrevButtons, viewModel.nextPrevButtons, viewModel::setNextPrevButtons)
    }
    group(key = "playback.pip", header = R.string.settings_section_pip) {
        switch(PlaybackIndex.autoPip, viewModel.autoPip, viewModel::setAutoPip)
        switch(PlaybackIndex.continueWatchingMiniPlayer, viewModel.continueWatchingMiniPlayer, viewModel::setContinueWatchingMiniPlayer)
        switch(PlaybackIndex.restoreMusicMiniPlayer, viewModel.restoreMusicMiniPlayer, viewModel::setRestoreMusicMiniPlayer)
        switch(PlaybackIndex.openMusicPlayerOnPlay, viewModel.openMusicPlayerOnPlay, viewModel::setOpenMusicPlayerOnPlay)
    }
    group(key = "playback.languages", header = R.string.settings_section_language_captions) {
        choice(PlaybackIndex.audioLanguage, onClick = { openDialog(PlaybackDialog.AUDIO_LANGUAGE) }) {
            val code by viewModel.audioLanguage.collectAsStateWithLifecycle()
            audioLanguageLabel(code)
        }
        choice(PlaybackIndex.subtitleLanguage, onClick = { openDialog(PlaybackDialog.SUBTITLE_LANGUAGE) }) {
            val code by viewModel.subtitleLanguage.collectAsStateWithLifecycle()
            subtitleLanguageLabel(code)
        }
        switch(PlaybackIndex.autoCaptions, viewModel.autoCaptions, viewModel::setAutoCaptions)
    }
    watchPageSection(viewModel, state)
    shortsPlayerSection(viewModel, state, openDialog)
    group(key = "playback.music", header = R.string.settings_section_music) {
        switch(PlaybackIndex.endlessRadio, viewModel.endlessRadio, viewModel::setEndlessRadio)
        switch(PlaybackIndex.pauseMusicWhenMuted, viewModel.pauseMusicWhenMuted, viewModel::setPauseMusicWhenMuted)
        switch(PlaybackIndex.musicResume, viewModel.musicResume, viewModel::setMusicResume)
        switch(PlaybackIndex.resumeFromHeadphones, viewModel.resumeFromHeadphones, viewModel::setResumeFromHeadphones)
        switch(PlaybackIndex.musicSideSwipes, viewModel.musicSideSwipes, viewModel::setMusicSideSwipes)
        switch(PlaybackIndex.musicVideoSwitch, viewModel.musicVideoSwitch, viewModel::setMusicVideoSwitch)
        choice(PlaybackIndex.lyricsProviders, onClick = { openDialog(PlaybackDialog.LYRICS) }) {
            val providers by viewModel.lyricsProviders.collectAsStateWithLifecycle()
            pluralStringResource(
                R.plurals.lyrics_providers_enabled,
                providers.size,
                providers.count { it.enabled },
                providers.size,
            )
        }
    }
    group(key = "playback.advanced", header = R.string.settings_section_advanced) {
        nav(DestinationIndex.entry(SettingsDestination.BUFFER), onClick = { onNavigate(SettingsTarget(SettingsDestination.BUFFER)) })
    }
}

private fun SettingsListScope.watchPageSection(
    viewModel: PlaybackSettingsViewModel,
    state: PlaybackStructure,
) = group(key = "playback.watch_page", header = R.string.settings_section_watch_page) {
    switch(PlaybackIndex.relatedVideos, viewModel.relatedVideos, viewModel::setRelatedVideos)
    row(PlaybackIndex.relatedCardStyle.key) { shape ->
        val selected by viewModel.relatedCardStyle.collectAsStateWithLifecycle()
        SettingsToggleGroupRow(
            title = stringResource(PlaybackIndex.relatedCardStyle.title),
            summary = stringResource(R.string.content_settings_related_card_style_subtitle),
            options =
                listOf(
                    FlowToggleOption(PlayerRelatedCardStyle.COMPACT, stringResource(R.string.content_settings_related_card_compact)),
                    FlowToggleOption(PlayerRelatedCardStyle.FULL_WIDTH, stringResource(R.string.content_settings_related_card_full_width)),
                ),
            selected = selected,
            onSelected = viewModel::setRelatedCardStyle,
            shape = shape,
        )
    }
    row(PlaybackIndex.titleLines.key) { shape ->
        val selected by viewModel.titleLines.collectAsStateWithLifecycle()
        SettingsToggleGroupRow(
            title = stringResource(PlaybackIndex.titleLines.title),
            summary = stringResource(R.string.content_settings_video_title_lines_subtitle),
            options =
                listOf(
                    FlowToggleOption(1, stringResource(R.string.content_settings_title_lines_1)),
                    FlowToggleOption(2, stringResource(R.string.content_settings_title_lines_2)),
                    FlowToggleOption(3, stringResource(R.string.content_settings_title_lines_3)),
                    FlowToggleOption(UNLIMITED_TITLE_LINES, stringResource(R.string.content_settings_title_lines_unlimited)),
                ),
            selected = selected,
            onSelected = viewModel::setTitleLines,
            shape = shape,
        )
    }
    switch(PlaybackIndex.comments, viewModel.comments, viewModel::setComments)
    switch(PlaybackIndex.commentsPreview, viewModel.commentsPreview, viewModel::setCommentsPreview, enabled = state.comments)
    switch(PlaybackIndex.liveChat, viewModel.liveChat, viewModel::setLiveChat)
}

private fun SettingsListScope.shortsPlayerSection(
    viewModel: PlaybackSettingsViewModel,
    state: PlaybackStructure,
    openDialog: (PlaybackDialog) -> Unit,
) = group(key = "playback.shorts", header = R.string.settings_section_shorts_player) {
    row(PlaybackIndex.disableShortsPlayer.key) { shape ->
        FlowSwitchRow(
            title = stringResource(PlaybackIndex.disableShortsPlayer.title),
            supportingText = stringResource(R.string.content_settings_disable_shorts_player_subtitle),
            checked = state.disableShortsPlayer || !state.shortsContent,
            onCheckedChange = viewModel::setDisableShortsPlayer,
            enabled = state.shortsContent,
            shape = shape,
        )
    }
    switch(
        PlaybackIndex.shortsPlayerPrompt,
        viewModel.shortsPlayerPrompt,
        viewModel::setShortsPlayerPrompt,
        enabled = state.shortsContent && !state.disableShortsPlayer,
    )
    choice(PlaybackIndex.shortsPlaybackMode, onClick = { openDialog(PlaybackDialog.SHORTS_MODE) }) {
        val mode by viewModel.shortsPlaybackMode.collectAsStateWithLifecycle()
        val seconds by viewModel.shortsAutoScrollSeconds.collectAsStateWithLifecycle()
        shortsModeLabel(mode, seconds)
    }
    switch(PlaybackIndex.shortsBackgroundPlay, viewModel.shortsBackgroundPlay, viewModel::setShortsBackgroundPlay)
    switch(PlaybackIndex.shortsPip, viewModel.shortsPip, viewModel::setShortsPip)
    switch(PlaybackIndex.shortsContinueIntoFeed, viewModel.shortsContinueIntoFeed, viewModel::setShortsContinueIntoFeed)
}
