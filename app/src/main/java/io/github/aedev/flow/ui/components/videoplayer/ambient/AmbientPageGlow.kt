package io.github.aedev.flow.ui.components.videoplayer.ambient

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.aedev.flow.ui.theme.PlayerGround
import kotlin.math.min
import kotlin.math.roundToInt

/** The glow under the player reaches about one 16:9 player height down the page. */
private const val GLOW_HEIGHT_PER_WIDTH = 9f / 16f

/**
 * The glow on the page under the player: the frame's bottom edge stretched downward and fading
 * into the page colour. While composed it asks [frames] for the page light in this theme's tone.
 * Frames are read only in draw, in a layer of their own, so a new frame never recomposes the page.
 *
 * @param scrolledPx how far the page has scrolled past its top; the glow is gone once the rows it
 *   sits behind have scrolled a glow's height away.
 */
@Composable
fun AmbientPageGlow(
    frames: AmbientFrameHolder,
    scrolledPx: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val style =
        remember(colors.background, colors.onBackground) {
            AmbientPageStyle.Glow(
                strip = AmbientGlowTone.forStatusBar(PlayerGround),
                page = AmbientGlowTone.forSurface(colors.background, colors.onBackground),
            )
        }
    DisposableEffect(frames, style) {
        frames.pageStyle = style
        onDispose { if (frames.pageStyle === style) frames.pageStyle = null }
    }
    val appear = rememberAmbientAppear(frames)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = appear.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }.drawBehind {
                    val page = frames.state.page?.page ?: return@drawBehind
                    val glowHeight = min(size.height, size.width * GLOW_HEIGHT_PER_WIDTH)
                    val fade = (1f - scrolledPx() / glowHeight).coerceIn(0f, 1f)
                    if (fade <= 0f || glowHeight < 1f) return@drawBehind
                    drawImage(
                        image = page,
                        dstSize = IntSize(size.width.roundToInt(), glowHeight.roundToInt()),
                        alpha = fade,
                        filterQuality = FilterQuality.Low,
                    )
                },
    )
}

/**
 * The glow behind the status bar above the player: the frame's top edge stretched over the strip,
 * on the player's dark ground whatever the theme, since the strip keeps light icons.
 *
 * @param alpha the ground's own fade while the sheet collapses, read in the layer only.
 */
@Composable
fun AmbientStatusBarGlow(
    frames: AmbientFrameHolder,
    heightPx: Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val appear = rememberAmbientAppear(frames)
    val height = with(LocalDensity.current) { heightPx.toDp() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .graphicsLayer {
                    this.alpha = appear.value * alpha()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }.drawBehind {
                    val strip = frames.state.page?.strip ?: return@drawBehind
                    drawImage(
                        image = strip,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                },
    )
}

/** Fades the light in once its first frame exists, rather than popping in over the page. */
@Composable
internal fun rememberAmbientAppear(frames: AmbientFrameHolder): Animatable<Float, AnimationVector1D> {
    val appear = remember(frames) { Animatable(0f) }
    val ready by remember(frames) { derivedStateOf { frames.state.page != null } }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(appear, ready) { if (ready) appear.animateTo(1f, spec) else appear.snapTo(0f) }
    return appear
}
