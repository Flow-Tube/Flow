package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.artwork.ArtworkLoopQuality
import io.github.aedev.flow.data.music.artwork.ArtworkSource
import io.github.aedev.flow.ui.components.settings.SettingsGroupScope
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.PlayerAppearanceIndex

/** The choices the animated artwork rows offer, resolved once per screen. */
@Immutable
internal class ArtworkOptions(
    val source: List<FlowToggleOption<ArtworkSource>>,
    val quality: List<FlowToggleOption<ArtworkLoopQuality>>,
)

@Composable
internal fun rememberArtworkOptions(): ArtworkOptions =
    ArtworkOptions(
        source = ArtworkSource.entries.map { FlowToggleOption(it, stringResource(artworkSourceLabel(it))) },
        quality = ArtworkLoopQuality.entries.map { FlowToggleOption(it, stringResource(artworkQualityLabel(it))) },
    )

private fun artworkSourceLabel(source: ArtworkSource): Int =
    when (source) {
        ArtworkSource.APPLE_MUSIC -> R.string.player_appearance_animated_artwork_source_apple
        ArtworkSource.SPOTIFY_CANVAS -> R.string.player_appearance_animated_artwork_source_canvas
        ArtworkSource.BOTH -> R.string.player_appearance_animated_artwork_source_both
    }

private fun artworkQualityLabel(quality: ArtworkLoopQuality): Int =
    when (quality) {
        ArtworkLoopQuality.AUTO -> R.string.player_appearance_animated_artwork_quality_auto
        ArtworkLoopQuality.LOW -> R.string.player_appearance_animated_artwork_quality_low
        ArtworkLoopQuality.MEDIUM -> R.string.player_appearance_animated_artwork_quality_medium
        ArtworkLoopQuality.HIGH -> R.string.player_appearance_animated_artwork_quality_high
    }

/**
 * The animated artwork switch and, while it is on, where the loops come from and how they are
 * fetched. A Canvas comes in one size, so quality only shows while Apple Music's loops can play.
 */
internal fun SettingsGroupScope.animatedArtworkRows(
    viewModel: PlayerAppearanceViewModel,
    enabled: Boolean,
    source: ArtworkSource,
    options: ArtworkOptions,
) {
    switch(PlayerAppearanceIndex.animatedArtwork, enabled, viewModel::setAnimatedArtwork)
    if (!enabled) return
    toggleGroup(PlayerAppearanceIndex.animatedArtworkSource, options.source, source, viewModel::setAnimatedArtworkSource)
    if (source.asksAppleMusic) {
        toggleGroup(
            PlayerAppearanceIndex.animatedArtworkQuality,
            options.quality,
            viewModel.animatedArtworkQuality,
            viewModel::setAnimatedArtworkQuality,
        )
    }
    switch(PlayerAppearanceIndex.animatedArtworkWifiOnly, viewModel.animatedArtworkWifiOnly, viewModel::setAnimatedArtworkWifiOnly)
}
