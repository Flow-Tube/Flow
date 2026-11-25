package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.artwork.ArtworkLoopQuality
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.PlayerAppearanceIndex

@Composable
internal fun rememberArtworkQualityOptions(): List<FlowToggleOption<ArtworkLoopQuality>> =
    ArtworkLoopQuality.entries.map { FlowToggleOption(it, stringResource(artworkQualityLabel(it))) }

private fun artworkQualityLabel(quality: ArtworkLoopQuality): Int =
    when (quality) {
        ArtworkLoopQuality.AUTO -> R.string.player_appearance_animated_artwork_quality_auto
        ArtworkLoopQuality.LOW -> R.string.player_appearance_animated_artwork_quality_low
        ArtworkLoopQuality.MEDIUM -> R.string.player_appearance_animated_artwork_quality_medium
        ArtworkLoopQuality.HIGH -> R.string.player_appearance_animated_artwork_quality_high
    }

/** The animated artwork switch and, while it is on, how the loops are fetched. */
internal fun SettingsGroupScope.animatedArtworkRows(
    viewModel: PlayerAppearanceViewModel,
    enabled: Boolean,
    qualityOptions: List<FlowToggleOption<ArtworkLoopQuality>>,
) {
    switch(PlayerAppearanceIndex.animatedArtwork, enabled, viewModel::setAnimatedArtwork)
    if (!enabled) return
    switch(PlayerAppearanceIndex.animatedArtworkWifiOnly, viewModel.animatedArtworkWifiOnly, viewModel::setAnimatedArtworkWifiOnly)
    toggleGroup(
        PlayerAppearanceIndex.animatedArtworkQuality,
        qualityOptions,
        viewModel.animatedArtworkQuality,
        viewModel::setAnimatedArtworkQuality,
    )
}
