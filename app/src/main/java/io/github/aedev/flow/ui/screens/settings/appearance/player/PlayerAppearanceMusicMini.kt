package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.data.local.MiniCoverMotion
import io.github.aedev.flow.data.local.MiniCoverShape
import io.github.aedev.flow.ui.components.musicplayer.mini.coverShapeLabel
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.PlayerAppearanceIndex

internal enum class MusicMiniSheet { COVER_SHAPES, COVER_PAUSED }

/**
 * What the music mini player group shows and which of its sheets is open. Every field is state, so
 * the settings list reads them as it builds and rebuilds when one changes.
 */
@Stable
internal class MusicMiniSettings {
    var coverShape by mutableStateOf(MiniCoverShape.MORPHING)
    var coverShapes by mutableStateOf(emptyList<CoverShape>())
    var pausedShape by mutableStateOf<CoverShape?>(CoverShape.CIRCLE)
    var coverShapeOptions by mutableStateOf(emptyList<FlowToggleOption<MiniCoverShape>>())
    var coverMotionOptions by mutableStateOf(emptyList<FlowToggleOption<MiniCoverMotion>>())
    var coverShapesLabel by mutableStateOf("")
    var pausedShapeLabel by mutableStateOf("")
    var sheet by mutableStateOf<MusicMiniSheet?>(null)
}

@Composable
internal fun rememberMusicMiniSettings(viewModel: PlayerAppearanceViewModel): MusicMiniSettings {
    val settings = remember { MusicMiniSettings() }
    val coverShape by viewModel.miniCoverShape.collectAsStateWithLifecycle()
    val coverShapes by viewModel.miniCoverShapes.collectAsStateWithLifecycle()
    val pausedShape by viewModel.miniCoverPausedShape.collectAsStateWithLifecycle()
    settings.coverShape = coverShape
    settings.coverShapes = coverShapes
    settings.pausedShape = pausedShape
    settings.coverShapeOptions =
        listOf(
            FlowToggleOption(MiniCoverShape.MORPHING, stringResource(R.string.player_appearance_mini_cover_morphing)),
            FlowToggleOption(MiniCoverShape.CIRCLE, stringResource(R.string.player_appearance_mini_cover_circle)),
        )
    settings.coverMotionOptions =
        listOf(
            FlowToggleOption(MiniCoverMotion.ON_PLAY, stringResource(R.string.player_appearance_mini_cover_motion_on_play)),
            FlowToggleOption(MiniCoverMotion.ALWAYS, stringResource(R.string.player_appearance_mini_cover_motion_always)),
            FlowToggleOption(MiniCoverMotion.STILL, stringResource(R.string.player_appearance_mini_cover_motion_still)),
        )
    settings.coverShapesLabel = pluralStringResource(R.plurals.player_appearance_cover_shapes_count, coverShapes.size, coverShapes.size)
    settings.pausedShapeLabel = stringResource(pausedShape?.let { coverShapeLabel(it) } ?: R.string.player_appearance_cover_paused_keep)
    return settings
}

/** The music mini player's own rows: its cover, and how that cover moves. */
internal fun SettingsListScope.musicMiniGroup(
    settings: MusicMiniSettings,
    viewModel: PlayerAppearanceViewModel,
) = group(key = "player_appearance.music_mini", header = R.string.settings_section_music_mini_player) {
    toggleGroup(PlayerAppearanceIndex.miniCoverShape, settings.coverShapeOptions, settings.coverShape, viewModel::setMiniCoverShape)
    if (settings.coverShape == MiniCoverShape.MORPHING) {
        toggleGroup(
            PlayerAppearanceIndex.miniCoverMotion,
            settings.coverMotionOptions,
            viewModel.miniCoverMotion,
            viewModel::setMiniCoverMotion,
        )
        nav(PlayerAppearanceIndex.miniCoverShapes, value = settings.coverShapesLabel, onClick = {
            settings.sheet =
                MusicMiniSheet.COVER_SHAPES
        })
        nav(
            PlayerAppearanceIndex.miniCoverPausedShape,
            value = settings.pausedShapeLabel,
            onClick = { settings.sheet = MusicMiniSheet.COVER_PAUSED },
        )
    }
}

@Composable
internal fun MusicMiniSheets(
    settings: MusicMiniSettings,
    viewModel: PlayerAppearanceViewModel,
) {
    when (settings.sheet) {
        MusicMiniSheet.COVER_SHAPES -> {
            CoverShapeSheet(
                title = stringResource(R.string.player_appearance_cover_shapes_title),
                subtitle = stringResource(R.string.player_appearance_cover_shapes_subtitle),
                selected = settings.coverShapes.toSet(),
                multiple = true,
                onPick = { it?.let(viewModel::toggleMiniCoverShape) },
                onDismiss = { settings.sheet = null },
            )
        }

        MusicMiniSheet.COVER_PAUSED -> {
            CoverShapeSheet(
                title = stringResource(R.string.player_appearance_cover_paused_title),
                subtitle = stringResource(R.string.player_appearance_cover_paused_subtitle),
                selected = setOf(settings.pausedShape),
                multiple = false,
                onPick = viewModel::setMiniCoverPausedShape,
                onDismiss = { settings.sheet = null },
                keepTrackShape = true,
            )
        }

        null -> {
            Unit
        }
    }
}
