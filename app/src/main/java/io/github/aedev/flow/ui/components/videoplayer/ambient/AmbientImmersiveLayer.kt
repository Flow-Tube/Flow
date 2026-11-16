package io.github.aedev.flow.ui.components.videoplayer.ambient

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.aedev.flow.ui.theme.PlayerGround
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** How much of the light stays while the page is being scrolled. */
private const val SCROLL_DIM = 0.25f

/** How long after scrolling stops the light comes back. */
private const val SCROLL_RETURN_MS = 3_000L

/** The near zone of the light is about one 16:9 player height. */
private const val PLAYER_HEIGHT_PER_WIDTH = 9f / 16f

/**
 * The whole page under the player lit by the video. It paints the page colour itself, so the page
 * above it can drop its own background; cards and chips keep their opaque containers. The light
 * is read only in draw, in a layer of its own, so a new frame never recomposes the page.
 *
 * @param isScrolling whether the page is being scrolled; the light dims to a quarter meanwhile and
 *   comes back a few seconds after it stops.
 */
@Composable
fun AmbientImmersiveLayer(
    frames: AmbientFrameHolder,
    isScrolling: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var nearFraction by remember { mutableFloatStateOf(0.3f) }
    val style =
        remember(colors.background, colors.onBackground, nearFraction) {
            AmbientPageStyle.Immersive(
                strip = AmbientGlowTone.forStatusBar(PlayerGround),
                page = AmbientGlowTone.forSurface(colors.background, colors.onBackground, IMMERSIVE_GAIN_DARK, IMMERSIVE_MIX_LIGHT),
                nearFraction = nearFraction,
            )
        }
    DisposableEffect(frames, style) {
        frames.pageStyle = style
        onDispose { if (frames.pageStyle === style) frames.pageStyle = null }
    }
    val appear = rememberAmbientAppear(frames)
    val dim = remember { Animatable(1f) }
    val dimSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(dim) {
        snapshotFlow(isScrolling).collectLatest { scrolling ->
            if (scrolling) {
                dim.animateTo(SCROLL_DIM, dimSpec)
            } else {
                delay(SCROLL_RETURN_MS)
                dim.animateTo(1f, dimSpec)
            }
        }
    }
    val background = colors.background
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        // Rounded so a resize by a pixel or two does not restyle the light.
                        nearFraction = ((size.width * PLAYER_HEIGHT_PER_WIDTH / size.height) * 50f).roundToInt() / 50f
                    }
                }.graphicsLayer()
                .drawBehind {
                    drawRect(background)
                    val page = frames.state.page?.page ?: return@drawBehind
                    val alpha = appear.value * dim.value
                    if (alpha <= 0f) return@drawBehind
                    drawImage(
                        image = page,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = alpha,
                        filterQuality = FilterQuality.Low,
                    )
                },
    )
}
