package io.github.aedev.flow.ui.screens.settings.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CrossfadeCurve
import io.github.aedev.flow.data.local.SPEED_SWIPE_STEPS
import io.github.aedev.flow.data.local.SpeedSwipeSide
import io.github.aedev.flow.player.crossfade.CrossfadePolicy
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.utils.LanguageCatalog
import io.github.aedev.flow.utils.formatMultiplierLabel
import kotlin.math.roundToInt

private val SliderTopPadding = 8.dp
private const val MS_PER_SECOND = 1_000L

/** Every single-choice picker Player & playback opens, all on the shared choice dialog. */
@Composable
internal fun PlaybackDialogs(
    dialog: PlaybackDialog,
    viewModel: PlaybackSettingsViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        PlaybackDialog.AUTOPLAY_COUNTDOWN -> {
            val seconds by viewModel.autoplayCountdown.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_autoplay_countdown_title),
                description = stringResource(R.string.player_settings_autoplay_countdown_dialog_body),
                options = AutoplayCountdownOptions.map { FlowChoice(it, countdownLabel(it)) },
                selected = seconds,
                onSelect = viewModel::setAutoplayCountdown,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.LONG_PRESS_SPEED -> {
            val speed by viewModel.longPressSpeed.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_appearance_long_press_speed_title),
                options = LongPressSpeedOptions.map { FlowChoice(it, longPressSpeedLabel(it)) },
                selected = if (speed <= 0f) 0f else speed,
                onSelect = viewModel::setLongPressSpeed,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.DOUBLE_TAP_SEEK -> {
            val seconds by viewModel.doubleTapSeek.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_double_tap_seek_dialog_title),
                description = stringResource(R.string.player_settings_double_tap_seek_dialog_body),
                options = DoubleTapSeekOptions.map { FlowChoice(it, doubleTapSeekLabel(it)) },
                selected = seconds,
                onSelect = viewModel::setDoubleTapSeek,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.SPEED_SWIPE_SIDE -> {
            val side by viewModel.speedSwipeSide.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_speed_swipe),
                description = stringResource(R.string.player_settings_speed_swipe_dialog_body),
                options = SpeedSwipeSide.entries.map { FlowChoice(it, speedSwipeSideLabel(it)) },
                selected = side,
                onSelect = viewModel::setSpeedSwipeSide,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.SPEED_SWIPE_STEP -> {
            val step by viewModel.speedSwipeStep.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_speed_swipe_step),
                description = stringResource(R.string.player_settings_speed_swipe_step_dialog_body),
                options = SPEED_SWIPE_STEPS.map { FlowChoice(it, formatMultiplierLabel(it)) },
                selected = step,
                onSelect = viewModel::setSpeedSwipeStep,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.SEEK_ZONE_WIDTH -> {
            val zone by viewModel.seekZone.collectAsStateWithLifecycle()
            val seconds by viewModel.doubleTapSeek.collectAsStateWithLifecycle()
            SeekZoneWidthDialog(
                selected = zone,
                stepSeconds = seconds,
                onSelect = viewModel::setSeekZone,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.AUDIO_LANGUAGE -> {
            val code by viewModel.audioLanguage.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_audio_language_dialog_title),
                description = stringResource(R.string.player_settings_audio_language_dialog_body),
                options =
                    listOf(
                        FlowChoice(
                            PlaybackSettingsViewModel.ORIGINAL_AUDIO,
                            stringResource(R.string.player_settings_audio_original),
                            stringResource(R.string.player_settings_audio_original_desc),
                        ),
                    ) + languageChoices(),
                selected = code,
                onSelect = viewModel::setAudioLanguage,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.SUBTITLE_LANGUAGE -> {
            val code by viewModel.subtitleLanguage.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.player_settings_subtitle_language_dialog_title),
                description = stringResource(R.string.player_settings_subtitle_language_dialog_body),
                options =
                    listOf(
                        FlowChoice(
                            CaptionTrackResolver.NO_PREFERRED_LANGUAGE,
                            stringResource(R.string.player_settings_subtitle_language_none),
                            stringResource(R.string.player_settings_subtitle_language_none_desc),
                        ),
                    ) + languageChoices(),
                selected = code,
                onSelect = viewModel::setSubtitleLanguage,
                onDismiss = onDismiss,
            )
        }

        PlaybackDialog.SHORTS_MODE -> {
            ShortsModeDialog(viewModel = viewModel, onDismiss = onDismiss)
        }

        PlaybackDialog.LYRICS -> {
            LyricsProvidersSheet(viewModel = viewModel, onDismiss = onDismiss)
        }

        PlaybackDialog.CROSSFADE_DURATION -> {
            val ms by viewModel.crossfadeMs.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.settings_crossfade_duration_title),
                options = CrossfadePolicy.DURATIONS_MS.map { FlowChoice(it, crossfadeDurationLabel(it)) },
                selected = ms.toLong(),
                onSelect = viewModel::setCrossfadeMs,
                onDismiss = onDismiss,
                searchable = false,
            )
        }

        PlaybackDialog.CROSSFADE_CURVE -> {
            val curve by viewModel.crossfadeCurve.collectAsStateWithLifecycle()
            FlowChoiceDialog(
                title = stringResource(R.string.settings_crossfade_curve_title),
                options = CrossfadeCurve.entries.map { FlowChoice(it, crossfadeCurveLabel(it), crossfadeCurveDetail(it)) },
                selected = curve,
                onSelect = viewModel::setCrossfadeCurve,
                onDismiss = onDismiss,
                dismissOnSelect = false,
                footer = { CrossfadeCurveChart(curve = curve, modifier = Modifier.padding(top = SliderTopPadding)) },
            )
        }
    }
}

@Composable
internal fun crossfadeDurationLabel(ms: Long): String =
    if (ms < MS_PER_SECOND) {
        stringResource(R.string.settings_milliseconds_value, ms.toString())
    } else {
        stringResource(R.string.settings_seconds_value, (ms / MS_PER_SECOND).toString())
    }

@Composable
internal fun crossfadeCurveLabel(curve: CrossfadeCurve): String =
    stringResource(
        when (curve) {
            CrossfadeCurve.EQUAL_POWER -> R.string.crossfade_curve_equal_power
            CrossfadeCurve.S_CURVE -> R.string.crossfade_curve_s_curve
            CrossfadeCurve.HOLD -> R.string.crossfade_curve_hold
            CrossfadeCurve.GENTLE -> R.string.crossfade_curve_gentle
        },
    )

@Composable
private fun crossfadeCurveDetail(curve: CrossfadeCurve): String =
    stringResource(
        when (curve) {
            CrossfadeCurve.EQUAL_POWER -> R.string.crossfade_curve_equal_power_detail
            CrossfadeCurve.S_CURVE -> R.string.crossfade_curve_s_curve_detail
            CrossfadeCurve.HOLD -> R.string.crossfade_curve_hold_detail
            CrossfadeCurve.GENTLE -> R.string.crossfade_curve_gentle_detail
        },
    )

@Composable
private fun languageChoices(): List<FlowChoice<String>> =
    remember {
        LanguageCatalog.codes
            .map { code ->
                val localized = LanguageCatalog.displayName(code)
                val native = LanguageCatalog.nativeName(code)
                FlowChoice(code, localized, native.takeIf { it != localized })
            }.sortedBy { it.label }
    }

/**
 * Loop, advance, or advance on a timer. The timer's slider sits under the choices and only shows
 * for the timed mode; it commits when the drag ends.
 */
@Composable
private fun ShortsModeDialog(
    viewModel: PlaybackSettingsViewModel,
    onDismiss: () -> Unit,
) {
    val mode by viewModel.shortsPlaybackMode.collectAsStateWithLifecycle()
    val seconds by viewModel.shortsAutoScrollSeconds.collectAsStateWithLifecycle()
    var draft by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    FlowChoiceDialog(
        title = stringResource(R.string.player_settings_shorts_playback_mode_dialog_title),
        description = stringResource(R.string.player_settings_shorts_playback_mode_dialog_body),
        options =
            listOf(
                FlowChoice(ShortsPlaybackModes.LOOP, stringResource(R.string.player_settings_shorts_playback_mode_loop)),
                FlowChoice(ShortsPlaybackModes.AUTO_NEXT, stringResource(R.string.player_settings_shorts_playback_mode_auto_next)),
                FlowChoice(ShortsPlaybackModes.AUTO_INTERVAL, stringResource(R.string.player_settings_shorts_playback_mode_auto_interval)),
            ),
        selected = mode,
        onSelect = viewModel::setShortsPlaybackMode,
        onDismiss = onDismiss,
        dismissOnSelect = false,
        footer = {
            if (mode == ShortsPlaybackModes.AUTO_INTERVAL) {
                Column(Modifier.padding(top = SliderTopPadding)) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.player_settings_shorts_auto_scroll_seconds_template,
                                draft.roundToInt(),
                                draft.roundToInt(),
                            ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        onValueChangeFinished = { viewModel.setShortsAutoScrollSeconds(draft.roundToInt()) },
                        valueRange = SHORTS_AUTO_SCROLL_MIN.toFloat()..SHORTS_AUTO_SCROLL_MAX.toFloat(),
                        steps = SHORTS_AUTO_SCROLL_MAX - SHORTS_AUTO_SCROLL_MIN - 1,
                    )
                }
            }
        },
    )
}
