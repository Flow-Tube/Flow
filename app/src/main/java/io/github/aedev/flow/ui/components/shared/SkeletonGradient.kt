package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.aedev.flow.data.local.LoadingAnimation
import io.github.aedev.flow.data.local.ThumbnailQuality
import io.github.aedev.flow.ui.theme.tone
import io.github.aedev.flow.ui.theme.withTone

private const val THEME_TINT = 0.3f
private const val MID_TONE = 50.0
private const val START_TONE_DARK = 16.0
private const val END_TONE_DARK = 24.0
private const val START_TONE_LIGHT = 92.0
private const val END_TONE_LIGHT = 84.0
private const val PULSE_TONE_STEP = 8.0

/**
 * The gradient a loading placeholder pulses between: [dimStart] to [dimEnd] at rest, and the
 * [brightStart] to [brightEnd] copy it fades up to.
 */
@Immutable
data class SkeletonGradient(
    val dimStart: Color,
    val dimEnd: Color,
    val brightStart: Color,
    val brightEnd: Color,
)

/** Whether loading placeholders draw a gradient. A user setting, off by default. */
val LocalSkeletonGradientEnabled = staticCompositionLocalOf { false }

/** How loading placeholders move. A user setting. */
val LocalLoadingAnimation = staticCompositionLocalOf { LoadingAnimation.PULSE }

/**
 * The tone [gain] times as far from [rest] as [peak] is, so a pulse between two close surface tones
 * can be made to swing wider in the same direction.
 */
internal fun stretchTone(
    rest: Color,
    peak: Color,
    gain: Float,
): Color =
    Color(
        red = (rest.red + (peak.red - rest.red) * gain).coerceIn(0f, 1f),
        green = (rest.green + (peak.green - rest.green) * gain).coerceIn(0f, 1f),
        blue = (rest.blue + (peak.blue - rest.blue) * gain).coerceIn(0f, 1f),
        alpha = peak.alpha,
    )

/** Overrides the theme's gradient, for placeholders that belong to one piece of artwork. */
internal val LocalSkeletonGradient = compositionLocalOf<SkeletonGradient?> { null }

@Composable
internal fun themeSkeletonGradient(): SkeletonGradient {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.primary) {
        SkeletonGradient(
            dimStart = scheme.surfaceContainerHigh,
            dimEnd = lerp(scheme.surfaceContainerHigh, scheme.primary, THEME_TINT),
            brightStart = scheme.surfaceContainerHighest,
            brightEnd = lerp(scheme.surfaceContainerHighest, scheme.primary, THEME_TINT),
        )
    }
}

/**
 * Placeholders in [content] take their gradient from the colours of [thumbnailUrl]. Hue comes from
 * the picture and tone from the app's surface, so a loud thumbnail can't make the page glare. They
 * show the theme's gradient until the picture has been read, which on a slow connection can be the
 * whole time the page loads, and ease across once it has.
 */
@Composable
fun ThumbnailSkeletonGradient(
    thumbnailUrl: String?,
    content: @Composable () -> Unit,
) = key(thumbnailUrl) {
    val palette =
        rememberMediaPalette(
            thumbnailUrl = thumbnailUrl,
            animated = false,
            cacheOnly = LocalThumbnailQuality.current == ThumbnailQuality.OFF,
        )
    val scheme = MaterialTheme.colorScheme
    val theme = themeSkeletonGradient()
    val dark = scheme.surface.tone() < MID_TONE
    val target =
        remember(palette.base, palette.accent, dark, theme) {
            if (palette.accent == scheme.primary && palette.base == scheme.surface) {
                theme
            } else {
                val towardsSurface = if (dark) PULSE_TONE_STEP else -PULSE_TONE_STEP
                val startTone = if (dark) START_TONE_DARK else START_TONE_LIGHT
                val endTone = if (dark) END_TONE_DARK else END_TONE_LIGHT
                SkeletonGradient(
                    dimStart = palette.base.withTone(startTone),
                    dimEnd = palette.accent.withTone(endTone),
                    brightStart = palette.base.withTone(startTone + towardsSurface),
                    brightEnd = palette.accent.withTone(endTone + towardsSurface),
                )
            }
        }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Color>()
    val dimStart by animateColorAsState(target.dimStart, spec, label = "skeletonDimStart")
    val dimEnd by animateColorAsState(target.dimEnd, spec, label = "skeletonDimEnd")
    val brightStart by animateColorAsState(target.brightStart, spec, label = "skeletonBrightStart")
    val brightEnd by animateColorAsState(target.brightEnd, spec, label = "skeletonBrightEnd")
    CompositionLocalProvider(
        LocalSkeletonGradient provides SkeletonGradient(dimStart, dimEnd, brightStart, brightEnd),
        content = content,
    )
}
