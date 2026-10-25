package io.github.aedev.flow.ui.screens.settings.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.BufferProfile
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsFootnote
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.option
import io.github.aedev.flow.ui.components.settings.slider
import io.github.aedev.flow.ui.screens.settings.index.BufferIndex
import java.util.Locale
import kotlin.math.roundToInt

private const val MS_PER_SECOND = 1000f
private val PresetProfiles = listOf(BufferProfile.STABLE, BufferProfile.AGGRESSIVE, BufferProfile.DATASAVER)

// The ranges end where the player's caps do, so a slider never offers a value it would ignore (#1438).
private val MinBufferRange = 1_000..PlayerConfig.MAX_SAFE_MAIN_MIN_BUFFER_MS
private val MaxBufferRange = 30_000..PlayerConfig.MAX_SAFE_MAIN_BUFFER_MS

/** How much video the player holds ahead: a preset, or the four durations set by hand. */
@Composable
internal fun BufferSettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: BufferSettingsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val minBuffer by viewModel.minBufferMs.collectAsStateWithLifecycle()
    val maxBuffer by viewModel.maxBufferMs.collectAsStateWithLifecycle()
    val startBuffer by viewModel.startBufferMs.collectAsStateWithLifecycle()
    val rebuffer by viewModel.rebufferMs.collectAsStateWithLifecycle()
    val profileLabels = BufferProfile.entries.associateWith { profileLabel(it) }

    SettingsPage(
        title = stringResource(R.string.buffer_settings_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = BufferIndex.profile.key, header = R.string.settings_buffer_profiles, footer = R.string.buffer_settings_desc) {
            PresetProfiles.forEach { preset ->
                option(
                    key = "buffer.profile.${preset.name}",
                    label = profileLabels.getValue(preset).first,
                    supportingText = profileLabels.getValue(preset).second,
                    selected = profile == preset,
                    onClick = { viewModel.setProfile(preset) },
                )
            }
        }
        group(key = "buffer.custom", header = R.string.buffer_settings_header_custom, footer = R.string.buffer_custom_mode_desc) {
            option(
                key = "buffer.profile.CUSTOM",
                label = profileLabels.getValue(BufferProfile.CUSTOM).first,
                supportingText = profileLabels.getValue(BufferProfile.CUSTOM).second,
                selected = profile == BufferProfile.CUSTOM,
                onClick = { viewModel.setProfile(BufferProfile.CUSTOM) },
            )
            if (profile == BufferProfile.CUSTOM) {
                millisSlider(BufferIndex.minBuffer, minBuffer, MinBufferRange, 1_000, viewModel::setMinBuffer)
                millisSlider(BufferIndex.maxBuffer, maxBuffer, MaxBufferRange, 5_000, viewModel::setMaxBuffer)
                millisSlider(BufferIndex.startBuffer, startBuffer, 500..5_000, 500, viewModel::setStartBuffer)
                millisSlider(BufferIndex.rebuffer, rebuffer, 1_000..10_000, 500, viewModel::setRebuffer)
            }
        }
        if (profile == BufferProfile.CUSTOM) {
            item("buffer.custom.memory") { SettingsFootnote(stringResource(R.string.buffer_long_memory_note)) }
        }
        item("buffer.restart") { SettingsFootnote(stringResource(R.string.cache_size_applies_on_restart)) }
    }
}

private fun SettingsGroupScope.millisSlider(
    entry: SettingEntry,
    valueMs: Int,
    range: IntRange,
    stepMs: Int,
    onCommit: (Int) -> Unit,
) = slider(
    entry = entry,
    value = valueMs.coerceIn(range).toFloat(),
    onValueCommitted = { committed -> onCommit(((committed / stepMs).roundToInt() * stepMs).coerceIn(range)) },
    valueRange = range.first.toFloat()..range.last.toFloat(),
    steps = (range.last - range.first) / stepMs - 1,
    valueLabel = { stringResource(R.string.settings_seconds_value, secondsText(it.roundToInt())) },
)

private fun secondsText(ms: Int): String =
    String.format(Locale.getDefault(), "%.1f", ms / MS_PER_SECOND).removeSuffix(".0").removeSuffix(",0")

@Composable
private fun profileLabel(profile: BufferProfile): Pair<String, String?> =
    when (profile) {
        BufferProfile.STABLE -> {
            stringResource(R.string.buffer_profile_stable) to profileSummary(R.string.buffer_desc_stable, profile)
        }

        BufferProfile.AGGRESSIVE -> {
            stringResource(R.string.buffer_profile_aggressive) to
                profileSummary(R.string.buffer_desc_aggressive, profile)
        }

        BufferProfile.DATASAVER -> {
            stringResource(R.string.buffer_profile_datasaver) to
                profileSummary(R.string.buffer_desc_datasaver, profile)
        }

        BufferProfile.CUSTOM -> {
            stringResource(R.string.buffer_profile_custom) to stringResource(R.string.buffer_profile_custom_desc)
        }
    }

@Composable
private fun profileSummary(
    description: Int,
    profile: BufferProfile,
): String =
    stringResource(
        R.string.settings_value_pair,
        stringResource(description),
        stringResource(R.string.settings_buffer_range, secondsText(profile.minBuffer), secondsText(profile.maxBuffer)),
    )
