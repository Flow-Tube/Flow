package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LyricsAnimationStyle
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.local.LyricsGlow
import io.github.aedev.flow.ui.components.musicplayer.lyrics.LyricsAlignments
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsAlignmentLabel
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsAnimationLabel
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsBackgroundLabel
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsGlowLabel
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.PlayerAppearanceIndex

/** The choices the lyrics group offers, resolved once per screen. */
@Immutable
internal class LyricsLookOptions(
    val animation: List<FlowToggleOption<LyricsAnimationStyle>>,
    val glow: List<FlowToggleOption<LyricsGlow>>,
    val background: List<FlowToggleOption<LyricsBackgroundStyle>>,
    val alignment: List<FlowToggleOption<String>>,
)

@Composable
internal fun rememberLyricsLookOptions(): LyricsLookOptions =
    LyricsLookOptions(
        animation = LyricsAnimationStyle.entries.map { FlowToggleOption(it, stringResource(lyricsAnimationLabel(it))) },
        glow = LyricsGlow.entries.map { FlowToggleOption(it, stringResource(lyricsGlowLabel(it))) },
        background = LyricsBackgroundStyle.entries.map { FlowToggleOption(it, stringResource(lyricsBackgroundLabel(it))) },
        alignment = LyricsAlignments.map { FlowToggleOption(it, stringResource(lyricsAlignmentLabel(it))) },
    )

/** How synced lyrics look and move, beside the music player's other looks. */
internal fun SettingsListScope.lyricsLookGroup(
    viewModel: PlayerAppearanceViewModel,
    options: LyricsLookOptions,
) = group(key = "player_appearance.lyrics", header = R.string.settings_section_lyrics) {
    toggleGroup(
        PlayerAppearanceIndex.lyricsAnimation,
        options.animation,
        viewModel.lyricsAnimationStyle,
        viewModel::setLyricsAnimationStyle,
    )
    toggleGroup(PlayerAppearanceIndex.lyricsGlow, options.glow, viewModel.lyricsGlow, viewModel::setLyricsGlow)
    switch(PlayerAppearanceIndex.lyricsBlurOtherLines, viewModel.lyricsBlurOtherLines, viewModel::setLyricsBlurOtherLines)
    toggleGroup(PlayerAppearanceIndex.lyricsBackground, options.background, viewModel.lyricsBackground, viewModel::setLyricsBackground)
    toggleGroup(PlayerAppearanceIndex.lyricsAlignment, options.alignment, viewModel.lyricsTextAlign, viewModel::setLyricsTextAlign)
}
