package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.local.GestureOverlayStyle
import io.github.aedev.flow.data.local.IdleProgressBar
import io.github.aedev.flow.data.local.MAX_FULLSCREEN_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.MAX_PORTRAIT_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.data.local.MusicPlainControlColors
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.QueueSwipeAction
import io.github.aedev.flow.data.local.ScrubPreviewStyle
import io.github.aedev.flow.data.local.SeekbarPaddingMode
import io.github.aedev.flow.data.local.ShortsPlayerUiMode
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.data.local.resolveSeekbarHorizontalPaddingDp
import io.github.aedev.flow.ui.components.musicplayer.common.canTurnLight
import io.github.aedev.flow.ui.components.musicplayer.queue.labelRes
import io.github.aedev.flow.ui.components.musicplayer.queue.rowSwipe
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsPreviewSheet
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.slider
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.labelRes
import io.github.aedev.flow.ui.screens.settings.index.PlayerAppearanceIndex
import kotlin.math.roundToInt

private const val SEEKBAR_STEP_DP = 4
private val PortraitSeekbarModes = listOf(SeekbarPaddingMode.SPACED, SeekbarPaddingMode.FULL_WIDTH, SeekbarPaddingMode.CUSTOM)
private val FullscreenSeekbarModes = listOf(SeekbarPaddingMode.FULL_WIDTH, SeekbarPaddingMode.DEFAULT, SeekbarPaddingMode.CUSTOM)

private enum class PreviewSheet { SLIDER, BACKGROUND, GESTURE, SWIPE_LEFT, SWIPE_RIGHT, QUEUE_SWIPE_LEFT, QUEUE_SWIPE_RIGHT }

/**
 * How the players look: seek bar, music background, video player surfaces, Shorts overlay and the
 * mini player. What the players do — gestures, speeds, autoplay — lives under Player & playback.
 */
@Composable
internal fun PlayerAppearanceScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: PlayerAppearanceViewModel = hiltViewModel(),
) {
    val sliderStyle by viewModel.sliderStyle.collectAsStateWithLifecycle()
    val portraitMode by viewModel.portraitSeekbarMode.collectAsStateWithLifecycle()
    val portraitCustom by viewModel.portraitSeekbarCustom.collectAsStateWithLifecycle()
    val fullscreenMode by viewModel.fullscreenSeekbarMode.collectAsStateWithLifecycle()
    val fullscreenCustom by viewModel.fullscreenSeekbarCustom.collectAsStateWithLifecycle()
    val idleProgressBar by viewModel.idleProgressBar.collectAsStateWithLifecycle()
    val scrubPreview by viewModel.scrubPreview.collectAsStateWithLifecycle()
    val frameStep by viewModel.frameStepButtons.collectAsStateWithLifecycle()
    val musicBackground by viewModel.musicBackground.collectAsStateWithLifecycle()
    val hideMusicArtwork by viewModel.hideMusicArtwork.collectAsStateWithLifecycle()
    val followLightTheme by viewModel.followLightTheme.collectAsStateWithLifecycle()
    val artworkControlColors by viewModel.artworkControlColors.collectAsStateWithLifecycle()
    val animatedArtwork by viewModel.animatedArtwork.collectAsStateWithLifecycle()
    val plainControlColors by viewModel.plainControlColors.collectAsStateWithLifecycle()
    val sleepTimerButton by viewModel.sleepTimerButton.collectAsStateWithLifecycle()
    val adaptiveSize by viewModel.adaptivePlayerSize.collectAsStateWithLifecycle()
    val ambientMode by viewModel.ambientMode.collectAsStateWithLifecycle()
    val videoArtworkColors by viewModel.videoArtworkColors.collectAsStateWithLifecycle()
    val paidPromotion by viewModel.paidPromotionLabel.collectAsStateWithLifecycle()
    val groupedQuality by viewModel.groupedQualitySelector.collectAsStateWithLifecycle()
    val gestureOverlay by viewModel.gestureOverlay.collectAsStateWithLifecycle()
    val shortsUi by viewModel.shortsUiMode.collectAsStateWithLifecycle()
    val miniPlayerScale by viewModel.miniPlayerScale.collectAsStateWithLifecycle()
    val miniPlayerSkip by viewModel.miniPlayerSkip.collectAsStateWithLifecycle()
    val miniPlayerNextPrev by viewModel.miniPlayerNextPrev.collectAsStateWithLifecycle()
    val miniBarSwipeLeft by viewModel.miniBarSwipeLeft.collectAsStateWithLifecycle()
    val miniBarSwipeRight by viewModel.miniBarSwipeRight.collectAsStateWithLifecycle()
    val queueSwipeLeft by viewModel.queueSwipeLeft.collectAsStateWithLifecycle()
    val queueSwipeRight by viewModel.queueSwipeRight.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<PreviewSheet?>(null) }

    val portraitPadding =
        resolveSeekbarHorizontalPaddingDp(
            portraitMode,
            portraitCustom,
            DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP,
            MAX_PORTRAIT_SEEKBAR_PADDING_DP,
        )
    val fullscreenPadding =
        resolveSeekbarHorizontalPaddingDp(
            fullscreenMode,
            fullscreenCustom,
            DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP,
            MAX_FULLSCREEN_SEEKBAR_PADDING_DP,
        )
    val portraitOptions = PortraitSeekbarModes.map { FlowToggleOption(it, stringResource(seekbarModeLabel(it))) }
    val fullscreenOptions = FullscreenSeekbarModes.map { FlowToggleOption(it, stringResource(seekbarModeLabel(it))) }
    val idleProgressOptions =
        listOf(
            FlowToggleOption(IdleProgressBar.ON_VIDEO, stringResource(R.string.player_appearance_idle_progress_on_video)),
            FlowToggleOption(IdleProgressBar.BELOW_VIDEO, stringResource(R.string.player_appearance_idle_progress_below)),
            FlowToggleOption(IdleProgressBar.OFF, stringResource(R.string.player_appearance_idle_progress_off)),
        )
    val scrubOptions =
        listOf(
            FlowToggleOption(ScrubPreviewStyle.STRIP, stringResource(R.string.player_appearance_scrub_preview_strip)),
            FlowToggleOption(ScrubPreviewStyle.FRAME, stringResource(R.string.player_appearance_scrub_preview_frame)),
        )
    val shortsOptions = ShortsPlayerUiMode.entries.map { FlowToggleOption(it, stringResource(shortsUiModeLabel(it))) }
    val plainControlOptions =
        listOf(
            FlowToggleOption(
                MusicPlainControlColors.MONOCHROME,
                stringResource(R.string.player_appearance_plain_control_colors_monochrome),
            ),
            FlowToggleOption(MusicPlainControlColors.APP_THEME, stringResource(R.string.player_appearance_plain_control_colors_app)),
        )
    val sidePanelOptions = FullscreenSidePanelWidth.entries.map { FlowToggleOption(it, stringResource(sidePanelWidthLabel(it))) }
    val miniPlayerOptions = MiniPlayerSize.entries.map { FlowToggleOption(it, stringResource(miniPlayerSizeLabel(it))) }
    val portraitSummary = stringResource(R.string.player_fullscreen_seekbar_width_subtitle, portraitPadding)
    val fullscreenSummary = stringResource(R.string.player_fullscreen_seekbar_width_subtitle, fullscreenPadding)
    val sliderLabel = stringResource(sliderStyleLabel(sliderStyle))
    val backgroundLabel = stringResource(musicBackgroundLabel(musicBackground))
    val gestureLabel = stringResource(gestureOverlayLabel(gestureOverlay))
    val swipeLeftLabel = stringResource(miniBarSwipeLeft.labelRes)
    val swipeRightLabel = stringResource(miniBarSwipeRight.labelRes)
    val queueSwipeLeftLabel = stringResource(queueSwipeLeft.rowSwipe.labelRes)
    val queueSwipeRightLabel = stringResource(queueSwipeRight.rowSwipe.labelRes)

    SettingsPage(
        title = stringResource(R.string.player_appearance_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "player_appearance.seek_bar", header = R.string.settings_section_seek_bar) {
            nav(PlayerAppearanceIndex.sliderStyle, value = sliderLabel, onClick = { sheet = PreviewSheet.SLIDER })
            toggleGroup(
                PlayerAppearanceIndex.portraitWidth,
                portraitOptions,
                portraitMode,
                viewModel::setPortraitSeekbarMode,
                summary = portraitSummary,
                preview = { SeekbarWidthPreview(portraitPadding.dp) },
            )
            if (portraitMode == SeekbarPaddingMode.CUSTOM) {
                seekbarSlider(
                    PlayerAppearanceIndex.portraitCustom,
                    portraitCustom,
                    MAX_PORTRAIT_SEEKBAR_PADDING_DP,
                    viewModel::setPortraitSeekbarCustom,
                )
            }
            toggleGroup(
                PlayerAppearanceIndex.fullscreenWidth,
                fullscreenOptions,
                fullscreenMode,
                viewModel::setFullscreenSeekbarMode,
                summary = fullscreenSummary,
                preview = { SeekbarWidthPreview(fullscreenPadding.dp) },
            )
            if (fullscreenMode == SeekbarPaddingMode.CUSTOM) {
                seekbarSlider(
                    PlayerAppearanceIndex.fullscreenCustom,
                    fullscreenCustom,
                    MAX_FULLSCREEN_SEEKBAR_PADDING_DP,
                    viewModel::setFullscreenSeekbarCustom,
                )
            }
            toggleGroup(PlayerAppearanceIndex.idleProgressBar, idleProgressOptions, idleProgressBar, viewModel::setIdleProgressBar)
            toggleGroup(PlayerAppearanceIndex.scrubPreview, scrubOptions, scrubPreview, viewModel::setScrubPreview)
            switch(PlayerAppearanceIndex.frameStep, frameStep, viewModel::setFrameStepButtons)
        }
        group(key = "player_appearance.music", header = R.string.settings_section_music_player) {
            nav(PlayerAppearanceIndex.musicBackground, value = backgroundLabel, onClick = { sheet = PreviewSheet.BACKGROUND })
            if (musicBackground.canTurnLight) {
                switch(PlayerAppearanceIndex.followLightTheme, followLightTheme, viewModel::setFollowLightTheme)
            }
            switch(PlayerAppearanceIndex.hideMusicArtwork, hideMusicArtwork, viewModel::setHideMusicArtwork)
            switch(PlayerAppearanceIndex.animatedArtwork, animatedArtwork, viewModel::setAnimatedArtwork)
            if (animatedArtwork) {
                switch(
                    PlayerAppearanceIndex.animatedArtworkWifiOnly,
                    viewModel.animatedArtworkWifiOnly,
                    viewModel::setAnimatedArtworkWifiOnly,
                )
            }
            switch(PlayerAppearanceIndex.artworkControlColors, artworkControlColors, viewModel::setArtworkControlColors)
            if (!artworkControlColors) {
                toggleGroup(
                    PlayerAppearanceIndex.plainControlColors,
                    plainControlOptions,
                    plainControlColors,
                    viewModel::setPlainControlColors,
                )
            }
            switch(PlayerAppearanceIndex.sleepTimerButton, sleepTimerButton, viewModel::setSleepTimerButton)
            nav(PlayerAppearanceIndex.queueSwipeLeft, value = queueSwipeLeftLabel, onClick = { sheet = PreviewSheet.QUEUE_SWIPE_LEFT })
            nav(PlayerAppearanceIndex.queueSwipeRight, value = queueSwipeRightLabel, onClick = { sheet = PreviewSheet.QUEUE_SWIPE_RIGHT })
        }
        group(key = "player_appearance.video", header = R.string.settings_section_video_player) {
            switch(PlayerAppearanceIndex.adaptiveSize, adaptiveSize, viewModel::setAdaptivePlayerSize)
            switch(PlayerAppearanceIndex.ambientMode, ambientMode, viewModel::setAmbientMode)
            switch(PlayerAppearanceIndex.videoArtworkColors, videoArtworkColors, viewModel::setVideoArtworkColors)
            switch(PlayerAppearanceIndex.groupedQuality, groupedQuality, viewModel::setGroupedQualitySelector)
            switch(PlayerAppearanceIndex.paidPromotion, paidPromotion, viewModel::setPaidPromotionLabel)
            nav(PlayerAppearanceIndex.gestureOverlay, value = gestureLabel, onClick = { sheet = PreviewSheet.GESTURE })
            toggleGroup(
                PlayerAppearanceIndex.sidePanelWidth,
                sidePanelOptions,
                viewModel.fullscreenSidePanelWidth,
                viewModel::setFullscreenSidePanelWidth,
            )
        }
        group(key = "player_appearance.shorts", header = R.string.content_settings_header_shorts) {
            toggleGroup(PlayerAppearanceIndex.shortsUi, shortsOptions, shortsUi, viewModel::setShortsUiMode)
        }
        group(key = "player_appearance.mini_player", header = R.string.settings_section_mini_player) {
            toggleGroup(
                PlayerAppearanceIndex.miniPlayerSize,
                miniPlayerOptions,
                MiniPlayerSize.nearest(miniPlayerScale),
                viewModel::setMiniPlayerSize,
            )
            switch(PlayerAppearanceIndex.miniPlayerSkip, miniPlayerSkip, viewModel::setMiniPlayerSkip)
            switch(PlayerAppearanceIndex.miniPlayerNextPrev, miniPlayerNextPrev, viewModel::setMiniPlayerNextPrev)
            nav(
                PlayerAppearanceIndex.miniBarSwipeLeft,
                value = swipeLeftLabel,
                onClick = { sheet = PreviewSheet.SWIPE_LEFT },
            )
            nav(
                PlayerAppearanceIndex.miniBarSwipeRight,
                value = swipeRightLabel,
                onClick = { sheet = PreviewSheet.SWIPE_RIGHT },
            )
        }
    }

    when (sheet) {
        PreviewSheet.SLIDER -> {
            SettingsPreviewSheet(
                title = stringResource(R.string.player_appearance_style_title),
                subtitle = stringResource(R.string.player_appearance_style_subtitle),
                options = SliderStyle.entries,
                selected = sliderStyle,
                label = { stringResource(sliderStyleLabel(it)) },
                onSelect = viewModel::setSliderStyle,
                onDismiss = { sheet = null },
                preview = { SliderStylePreview(it) },
            )
        }

        PreviewSheet.BACKGROUND -> {
            SettingsPreviewSheet(
                title = stringResource(R.string.player_background_style_title),
                options = MusicPlayerBackgroundStyle.entries,
                selected = musicBackground,
                label = { stringResource(musicBackgroundLabel(it)) },
                onSelect = viewModel::setMusicBackground,
                onDismiss = { sheet = null },
                preview = { MusicBackgroundPreview(it, followLightTheme) },
            )
        }

        PreviewSheet.GESTURE -> {
            SettingsPreviewSheet(
                title = stringResource(R.string.player_appearance_gesture_overlay_title),
                subtitle = stringResource(R.string.player_appearance_gesture_overlay_subtitle),
                options = GestureOverlayStyle.entries,
                selected = gestureOverlay,
                label = { stringResource(gestureOverlayLabel(it)) },
                onSelect = viewModel::setGestureOverlay,
                onDismiss = { sheet = null },
                preview = { GestureOverlayPreview(it) },
            )
        }

        PreviewSheet.SWIPE_LEFT, PreviewSheet.SWIPE_RIGHT -> {
            val left = sheet == PreviewSheet.SWIPE_LEFT
            SettingsPreviewSheet(
                title = stringResource(if (left) R.string.mini_bar_swipe_left else R.string.mini_bar_swipe_right),
                subtitle = stringResource(R.string.mini_bar_swipe_subtitle),
                options = MiniBarSwipeAction.entries,
                selected = if (left) miniBarSwipeLeft else miniBarSwipeRight,
                label = { stringResource(it.labelRes) },
                onSelect = { action -> if (left) viewModel.setMiniBarSwipeLeft(action) else viewModel.setMiniBarSwipeRight(action) },
                onDismiss = { sheet = null },
                preview = { MiniBarSwipePreview(it, towardsLeft = left) },
            )
        }

        PreviewSheet.QUEUE_SWIPE_LEFT, PreviewSheet.QUEUE_SWIPE_RIGHT -> {
            val left = sheet == PreviewSheet.QUEUE_SWIPE_LEFT
            SettingsPreviewSheet(
                title = stringResource(if (left) R.string.queue_swipe_left else R.string.queue_swipe_right),
                subtitle = stringResource(R.string.queue_swipe_subtitle),
                options = QueueSwipeAction.entries,
                selected = if (left) queueSwipeLeft else queueSwipeRight,
                label = { stringResource(it.rowSwipe.labelRes) },
                onSelect = { action -> if (left) viewModel.setQueueSwipeLeft(action) else viewModel.setQueueSwipeRight(action) },
                onDismiss = { sheet = null },
                preview = { QueueSwipePreview(it, towardsLeft = left) },
            )
        }

        null -> {
            Unit
        }
    }
}

private fun SettingsGroupScope.seekbarSlider(
    entry: SettingEntry,
    value: Int,
    max: Int,
    onCommit: (Int) -> Unit,
) = slider(
    entry = entry,
    value = value.toFloat(),
    onValueCommitted = { committed ->
        onCommit(((committed / SEEKBAR_STEP_DP).roundToInt() * SEEKBAR_STEP_DP).coerceIn(0, max))
    },
    valueRange = 0f..max.toFloat(),
    steps = max / SEEKBAR_STEP_DP - 1,
    valueLabel = { stringResource(R.string.player_fullscreen_seekbar_width_value, it.roundToInt()) },
)
