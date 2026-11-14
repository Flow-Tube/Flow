package io.github.aedev.flow.ui.components.musicplayer.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.aedev.flow.data.local.MusicPlainControlColors
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.ui.components.shared.MediaPalette
import io.github.aedev.flow.ui.components.shared.PaletteInkDark
import io.github.aedev.flow.ui.theme.contrastRatio
import io.github.aedev.flow.ui.theme.ensureContrastOn
import io.github.aedev.flow.ui.theme.tone
import io.github.aedev.flow.ui.theme.withTone

/**
 * DEFAULT background style follows the app theme; the artwork-based styles hand the whole player
 * over to a palette-derived scheme so every component keeps using MaterialTheme tokens. That
 * scheme is dark unless [light] asks for its light twin.
 */
@Composable
fun rememberMusicPlayerColorScheme(
    palette: MediaPalette,
    style: MusicPlayerBackgroundStyle,
    light: Boolean = false,
): ColorScheme {
    val appScheme = MaterialTheme.colorScheme
    return remember(palette, style, light, appScheme) {
        when {
            style == MusicPlayerBackgroundStyle.DEFAULT -> appScheme
            light -> paletteLightColorScheme(palette, appScheme.error)
            else -> paletteColorScheme(palette, appScheme.error)
        }
    }
}

/** The artwork styles that have a light version. Immersive and the cover's colours keep a dark scrim; black stays black. */
val MusicPlayerBackgroundStyle.canTurnLight: Boolean
    get() =
        this == MusicPlayerBackgroundStyle.GRADIENT ||
            this == MusicPlayerBackgroundStyle.BLUR ||
            this == MusicPlayerBackgroundStyle.BLUR_GRADIENT ||
            this == MusicPlayerBackgroundStyle.COLOR ||
            this == MusicPlayerBackgroundStyle.SHAPES

/** Whether the player draws its artwork style light: the switch is on and the app theme is light. */
fun musicPlayerTurnsLight(
    style: MusicPlayerBackgroundStyle,
    followLightTheme: Boolean,
    appSurface: Color,
): Boolean = followLightTheme && style.canTurnLight && appSurface.luminance() > 0.5f

/** The colours [PlayerBackground] paints with: the cover's two swatches over a ground. */
@Immutable
data class PlayerBackdropColors(
    val ground: Color,
    val base: Color,
    val accent: Color,
)

/**
 * Dark keeps the raw swatches over black. Light lifts both swatches to pale tones over the same
 * surface [paletteLightColorScheme] uses, so the backdrop and the controls agree.
 */
fun playerBackdropColors(
    base: Color,
    accent: Color,
    light: Boolean,
): PlayerBackdropColors =
    if (light) {
        PlayerBackdropColors(
            ground = lightSurfaceOf(base),
            base = base.withTone(90.0, maxChroma = 24.0),
            accent = accent.withTone(82.0, maxChroma = 36.0),
        )
    } else {
        PlayerBackdropColors(ground = Color.Black, base = base, accent = accent)
    }

private fun lightSurfaceOf(base: Color): Color = base.withTone(95.0, maxChroma = 12.0)

/**
 * Maps the raw artwork swatches onto M3 roles through CIELAB tone (≈ HCT tone): hue and chroma
 * come from the cover, but every role gets a fixed tone re-clamped against the surface, so
 * readability no longer depends on how light or dark the extracted swatches happen to be.
 */
internal fun paletteColorScheme(
    palette: MediaPalette,
    appError: Color,
): ColorScheme {
    val surface = palette.base.let { if (it.tone() > 30.0) it.withTone(26.0) else it }
    val ink = Color.White
    val accentSeed = palette.accent
    val accent = ensureContrastOn(accentSeed.withTone(80.0), surface, minRatio = 4.5f)
    val onAccent = accentSeed.withTone(20.0)
    // Tone 75 keeps the destructive tint clearly red yet darker than the app scheme's error,
    // and never dimmer than the white-ish body text sitting next to it.
    val error = ensureContrastOn(appError.withTone(75.0), surface, minRatio = 4.5f)
    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSeed.withTone(30.0),
        onPrimaryContainer = accentSeed.withTone(90.0),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = lerp(surface, ink, 0.16f),
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSeed.withTone(35.0),
        onTertiaryContainer = accentSeed.withTone(92.0),
        surface = surface,
        onSurface = ink,
        background = surface,
        onBackground = ink,
        surfaceVariant = lerp(surface, ink, 0.22f),
        onSurfaceVariant = ink.copy(alpha = 0.75f),
        surfaceContainerLowest = lerp(surface, Color.Black, 0.30f),
        surfaceContainerLow = lerp(surface, ink, 0.05f),
        surfaceContainer = lerp(surface, ink, 0.08f),
        surfaceContainerHigh = lerp(surface, ink, 0.11f),
        surfaceContainerHighest = lerp(surface, ink, 0.15f),
        outline = ink.copy(alpha = 0.35f),
        outlineVariant = ink.copy(alpha = 0.18f),
        surfaceTint = accent,
        error = error,
        onError = appError.withTone(15.0),
        errorContainer = appError.withTone(30.0),
        onErrorContainer = appError.withTone(90.0),
        inverseSurface = ink,
        inverseOnSurface = surface,
        inversePrimary = onAccent,
    )
}

/** The light twin of [paletteColorScheme]: the same roles, mirrored onto light tones. */
internal fun paletteLightColorScheme(
    palette: MediaPalette,
    appError: Color,
): ColorScheme {
    val surface = lightSurfaceOf(palette.base)
    val ink = PaletteInkDark
    val accentSeed = palette.accent
    val accent = ensureContrastOn(accentSeed.withTone(40.0), surface, minRatio = 4.5f)
    val onAccent = Color.White
    val error = ensureContrastOn(appError.withTone(40.0), surface, minRatio = 4.5f)
    return lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSeed.withTone(90.0, maxChroma = 30.0),
        onPrimaryContainer = accentSeed.withTone(10.0),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = lerp(surface, ink, 0.10f),
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSeed.withTone(88.0, maxChroma = 30.0),
        onTertiaryContainer = accentSeed.withTone(10.0),
        surface = surface,
        onSurface = ink,
        background = surface,
        onBackground = ink,
        surfaceVariant = lerp(surface, ink, 0.10f),
        onSurfaceVariant = ink.copy(alpha = 0.72f),
        surfaceContainerLowest = lerp(surface, Color.White, 0.5f),
        surfaceContainerLow = lerp(surface, ink, 0.03f),
        surfaceContainer = lerp(surface, ink, 0.05f),
        surfaceContainerHigh = lerp(surface, ink, 0.08f),
        surfaceContainerHighest = lerp(surface, ink, 0.11f),
        outline = ink.copy(alpha = 0.35f),
        outlineVariant = ink.copy(alpha = 0.14f),
        surfaceTint = accent,
        error = error,
        onError = Color.White,
        errorContainer = appError.withTone(90.0),
        onErrorContainer = appError.withTone(10.0),
        inverseSurface = ink,
        inverseOnSurface = surface,
        inversePrimary = accentSeed.withTone(80.0),
    )
}

/**
 * The accent only if it clears WCAG 3:1 against [container]; otherwise a guaranteed-contrast ink.
 */
internal fun readableAccentOn(
    container: Color,
    accent: Color,
): Color =
    when {
        contrastRatio(accent, container) >= 3f -> accent
        container.luminance() > 0.5f -> PaletteInkDark
        else -> Color.White
    }

/**
 * The scheme the player's controls (transport, seek bar, like and download, the action row, the
 * mini player's button and ring) draw with. Only the accent and container roles change, so text
 * and surfaces keep the player's own scheme: artwork colors, plain black and white against the
 * player's surface, or the app theme's accents.
 */
@Composable
fun rememberMusicControlColorScheme(
    playerScheme: ColorScheme,
    appScheme: ColorScheme,
    artworkColors: Boolean,
    plainColors: MusicPlainControlColors,
): ColorScheme =
    remember(playerScheme, appScheme, artworkColors, plainColors) {
        when {
            artworkColors -> playerScheme
            plainColors == MusicPlainControlColors.APP_THEME -> playerScheme.withControlRolesFrom(appScheme)
            else -> playerScheme.monochromeControls()
        }
    }

private fun ColorScheme.withControlRolesFrom(source: ColorScheme): ColorScheme =
    copy(
        primary = source.primary,
        onPrimary = source.onPrimary,
        primaryContainer = source.primaryContainer,
        onPrimaryContainer = source.onPrimaryContainer,
        secondaryContainer = source.secondaryContainer,
        onSecondaryContainer = source.onSecondaryContainer,
        tertiaryContainer = source.tertiaryContainer,
        onTertiaryContainer = source.onTertiaryContainer,
    )

/** White controls on a dark player, black on a light one; selected toggles fill solid. */
private fun ColorScheme.monochromeControls(): ColorScheme {
    val darkSurface = surface.luminance() < 0.5f
    val ink = if (darkSurface) Color.White else Color.Black
    val paper = if (darkSurface) Color.Black else Color.White
    return copy(
        primary = ink,
        onPrimary = paper,
        primaryContainer = ink,
        onPrimaryContainer = paper,
        secondaryContainer = ink.copy(alpha = 0.16f),
        onSecondaryContainer = ink,
        tertiaryContainer = ink,
        onTertiaryContainer = paper,
    )
}
