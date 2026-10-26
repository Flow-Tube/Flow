package io.github.aedev.flow.ui.screens.settings.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SpeedSwipeSide
import io.github.aedev.flow.notification.BackgroundWorkPolicy
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget

/** The pickers Player & playback opens over itself. */
internal enum class PlaybackDialog {
    AUTOPLAY_COUNTDOWN,
    LONG_PRESS_SPEED,
    DOUBLE_TAP_SEEK,
    SEEK_ZONE_WIDTH,
    SPEED_SWIPE_SIDE,
    SPEED_SWIPE_STEP,
    AUDIO_LANGUAGE,
    SUBTITLE_LANGUAGE,
    SHORTS_MODE,
    LYRICS,
}

/**
 * What the players do: autoplay, speeds, gestures, the buttons on the player, captions, the watch
 * page, the Shorts player and music. How the players look is Appearance › Player appearance.
 */
@Composable
internal fun PlaybackSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val loopAll by viewModel.loopAll.collectAsStateWithLifecycle()
    val customSpeeds by viewModel.customSpeeds.collectAsStateWithLifecycle()
    val doubleTapSeek by viewModel.doubleTapSeek.collectAsStateWithLifecycle()
    val speedSwipeSide by viewModel.speedSwipeSide.collectAsStateWithLifecycle()
    val musicSideSwipes by viewModel.musicSideSwipes.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val shortsContent by viewModel.shortsContent.collectAsStateWithLifecycle()
    val disableShortsPlayer by viewModel.disableShortsPlayer.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<PlaybackDialog?>(null) }
    val context = LocalContext.current
    var backgroundAllowed by remember { mutableStateOf(BackgroundWorkPolicy.isBackgroundWorkUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        backgroundAllowed = BackgroundWorkPolicy.isBackgroundWorkUnrestricted(context)
        onPauseOrDispose { }
    }

    SettingsPage(
        title = stringResource(R.string.settings_playback_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        playbackSections(
            viewModel = viewModel,
            state =
                PlaybackStructure(
                    loopAll,
                    customSpeeds,
                    doubleTapSeek > 0,
                    speedSwipeSide,
                    musicSideSwipes,
                    comments,
                    shortsContent,
                    disableShortsPlayer,
                    backgroundRestricted = !backgroundAllowed,
                ),
            openDialog = { dialog = it },
            onNavigate = onNavigate,
            onAllowBackground = { BackgroundWorkPolicy.requestUnrestrictedBackgroundWork(context) },
        )
    }

    dialog?.let { open ->
        PlaybackDialogs(dialog = open, viewModel = viewModel, onDismiss = { dialog = null })
    }
}

/** The values that decide which rows exist or are enabled; every other row reads its own state. */
internal data class PlaybackStructure(
    val loopAll: Boolean,
    val customSpeeds: Boolean,
    val doubleTapSeek: Boolean,
    val speedSwipeSide: SpeedSwipeSide,
    val musicSideSwipes: Boolean,
    val comments: Boolean,
    val shortsContent: Boolean,
    val disableShortsPlayer: Boolean,
    val backgroundRestricted: Boolean,
)
