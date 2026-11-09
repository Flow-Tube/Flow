package io.github.aedev.flow.ui.components.musicplayer.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.local.MusicPlainControlColors
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.components.shared.MediaPalette
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette

/**
 * How the full music player looks for the track on show: the cover's palette, the background style
 * and its colours, the player's own scheme and the scheme its controls draw with.
 */
@Immutable
internal class MusicPlayerAppearance(
    val palette: MediaPalette,
    val backdrop: PlayerBackdropColors,
    val backgroundStyle: MusicPlayerBackgroundStyle,
    val hideArtwork: Boolean,
    val sleepTimerButton: Boolean,
    val playerScheme: ColorScheme,
    val controlScheme: ColorScheme,
)

/**
 * Read once per player host from the user's player settings and the artwork at [thumbnailUrl].
 * [style] replaces the chosen background style for a surface that always draws one look.
 */
@Composable
internal fun rememberMusicPlayerAppearance(
    thumbnailUrl: String?,
    style: MusicPlayerBackgroundStyle? = null,
): MusicPlayerAppearance {
    val context = LocalContext.current
    val playerPreferences = remember { PlayerPreferences(context) }
    val chosenStyle by playerPreferences.musicPlayerBackgroundStyle.collectAsState(
        initial = MusicPlayerBackgroundStyle.BLUR_GRADIENT,
    )
    val backgroundStyle = style ?: chosenStyle
    val hideArtwork by playerPreferences.hideMusicPlayerArtwork.collectAsState(initial = false)
    val sleepTimerButton by playerPreferences.musicSleepTimerButton.collectAsState(initial = false)
    val followLightTheme by playerPreferences.musicPlayerFollowLightTheme.collectAsState(initial = false)
    val palette = rememberMediaPalette(thumbnailUrl)
    val lightPlayer = musicPlayerTurnsLight(backgroundStyle, followLightTheme, MaterialTheme.colorScheme.surface)
    val playerScheme = rememberMusicPlayerColorScheme(palette, backgroundStyle, lightPlayer)
    val backdrop = remember(palette, lightPlayer) { playerBackdropColors(palette.base, palette.accent, lightPlayer) }
    val artworkControlColors by playerPreferences.musicArtworkControlColors.collectAsState(initial = true)
    val plainControlColors by playerPreferences.musicPlainControlColors.collectAsState(initial = MusicPlainControlColors.MONOCHROME)
    val controlScheme =
        rememberMusicControlColorScheme(playerScheme, MaterialTheme.colorScheme, artworkControlColors, plainControlColors)
    return MusicPlayerAppearance(
        palette = palette,
        backdrop = backdrop,
        backgroundStyle = backgroundStyle,
        hideArtwork = hideArtwork,
        sleepTimerButton = sleepTimerButton,
        playerScheme = playerScheme,
        controlScheme = controlScheme,
    )
}
