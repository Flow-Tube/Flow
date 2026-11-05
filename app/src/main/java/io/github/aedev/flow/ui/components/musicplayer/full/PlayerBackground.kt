package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.ui.components.musicplayer.common.PlayerBackdropColors

@Composable
fun PlayerBackground(
    thumbnailUrl: String?,
    style: MusicPlayerBackgroundStyle,
    backdrop: PlayerBackdropColors,
    modifier: Modifier = Modifier,
    // Landscape layouts put the immersive art on the start side, where the controls are not.
    artworkAtStart: Boolean = false,
    // Immersive only: the album's motion artwork, playing where the still art is.
    animatedArtwork: (@Composable () -> Unit)? = null,
    // Covers to have ready in Coil's memory, for a layout whose artwork box doesn't load its neighbours.
    preloadCovers: List<String> = emptyList(),
) {
    val context = LocalContext.current
    LaunchedEffect(preloadCovers) { preloadCovers.forEach { context.imageLoader.execute(ImageRequest.Builder(context).data(it).build()) } }
    val baseColor = backdrop.base.copy(alpha = 0.78f)
    val accentColor = backdrop.accent.copy(alpha = 0.72f)
    val ground = backdrop.ground

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    if (style == MusicPlayerBackgroundStyle.DEFAULT) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        ground
                    },
                ),
    ) {
        when (style) {
            MusicPlayerBackgroundStyle.DEFAULT -> {
                Unit
            }

            MusicPlayerBackgroundStyle.BLUR -> {
                BlurredArtworkLayer(thumbnailUrl = rememberLoadedCover(thumbnailUrl), alpha = 0.62f)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(ground.copy(alpha = 0.58f)),
                )
            }

            MusicPlayerBackgroundStyle.GRADIENT -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0.00f to accentColor,
                                            0.38f to baseColor,
                                            0.72f to ground.copy(alpha = 0.88f),
                                            1.00f to ground,
                                        ),
                                ),
                            ),
                )
            }

            MusicPlayerBackgroundStyle.IMMERSIVE -> {
                // Issue #954: full-bleed artwork holds the top of the screen, then hands off to
                // its own blurred continuation and a scrim so the controls stay readable.
                val cover = rememberLoadedCover(thumbnailUrl)
                BlurredArtworkLayer(thumbnailUrl = cover, alpha = 0.85f, crossfadeMillis = 450)
                ImmersiveArtworkLayer(thumbnailUrl = cover, atStart = artworkAtStart, animatedArtwork = animatedArtwork)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0.00f to Color.Transparent,
                                            0.52f to Color.Black.copy(alpha = 0.28f),
                                            1.00f to Color.Black.copy(alpha = 0.86f),
                                        ),
                                ),
                            ),
                )
            }

            MusicPlayerBackgroundStyle.BLUR_GRADIENT -> {
                BlurredArtworkLayer(thumbnailUrl = rememberLoadedCover(thumbnailUrl), alpha = 0.55f)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0.00f to ground.copy(alpha = 0.40f),
                                            0.30f to accentColor.copy(alpha = 0.22f),
                                            0.55f to baseColor.copy(alpha = 0.34f),
                                            0.80f to ground.copy(alpha = 0.80f),
                                            1.00f to ground.copy(alpha = 0.95f),
                                        ),
                                ),
                            ),
                )
            }
        }
    }
}

/**
 * The cover to draw for [url]: the previous one until [url] is in Coil's memory, so a crossfade always
 * has both pictures. A failed load lets go too, so the art can't stay stuck on the old song.
 */
@Composable
private fun rememberLoadedCover(url: String?): String? {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(url) }
    LaunchedEffect(url) {
        if (url != null) context.imageLoader.execute(ImageRequest.Builder(context).data(url).build())
        loaded = url
    }
    return loaded
}

@Composable
private fun ImmersiveArtworkLayer(
    thumbnailUrl: String?,
    atStart: Boolean,
    animatedArtwork: (@Composable () -> Unit)?,
) {
    // The fade-out mask is drawn over the still art and the loop together, so both melt into the
    // blurred continuation the same way.
    Box(
        modifier =
            Modifier
                .then(if (atStart) Modifier.fillMaxHeight().fillMaxWidth(IMMERSIVE_START_WIDTH_FRACTION) else Modifier.immersiveArtBox())
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush =
                            if (atStart) {
                                Brush.horizontalGradient(
                                    0.50f to Color.Black,
                                    1.00f to Color.Transparent,
                                )
                            } else {
                                Brush.verticalGradient(
                                    IMMERSIVE_FADE_START to Color.Black,
                                    1.00f to Color.Transparent,
                                )
                            },
                        blendMode = BlendMode.DstIn,
                    )
                },
    ) {
        AnimatedContent(
            targetState = thumbnailUrl,
            // The old cover stays solid under the new one, so the dark ground never shows through.
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(durationMillis = 1, delayMillis = 400)) },
            label = "playerImmersiveArt",
        ) { targetUrl ->
            AsyncImage(
                model = targetUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = if (atStart) Alignment.Center else Alignment.TopCenter,
            )
        }
        animatedArtwork?.invoke()
    }
}

@Composable
internal fun BlurredArtworkLayer(
    thumbnailUrl: String?,
    alpha: Float,
    crossfadeMillis: Int = 800,
) {
    AnimatedContent(
        targetState = thumbnailUrl,
        transitionSpec = { fadeIn(tween(crossfadeMillis)) togetherWith fadeOut(tween(crossfadeMillis)) },
        label = "playerBackgroundArt",
    ) { targetUrl ->
        AsyncImage(
            model = targetUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .blur(150.dp),
            alpha = alpha,
            contentScale = ContentScale.Crop,
        )
    }
}

/** Full width at the 3:4 of the album's tall loop, so nothing is cropped, unless the window is too short for it. */
private fun Modifier.immersiveArtBox(): Modifier =
    layout { measurable, constraints ->
        val height = minOf(constraints.maxWidth * 4 / 3, (constraints.maxHeight * IMMERSIVE_MAX_HEIGHT_FRACTION).toInt())
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** How much of a landscape window the immersive art covers from the start edge before it fades out. */
private const val IMMERSIVE_START_WIDTH_FRACTION = 0.62f

/** The most of a tall window's height the immersive art takes, keeping clear of the title and controls. */
private const val IMMERSIVE_MAX_HEIGHT_FRACTION = 0.7f

/** Where the art starts fading in its box, so it has melted away by the bottom edge. */
private const val IMMERSIVE_FADE_START = 0.72f
