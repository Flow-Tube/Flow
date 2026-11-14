@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private const val PORTRAIT_MORPH_SHARE = 0.3f
private const val STAGGER_MS = 70L

private val PortraitMorph: Morph by lazy { Morph(MaterialShapes.Circle.normalized(), MaterialShapes.Cookie9Sided.normalized()) }

/**
 * This shape scaled and centred in the unit square so that its farthest point touches the edge at
 * every rotation: turning or morphing between fitted shapes never leaves the slot or changes size.
 */
fun RoundedPolygon.fittedToCircle(): RoundedPolygon {
    val bounds = calculateMaxBounds()
    val centerX = (bounds[0] + bounds[2]) / 2f
    val centerY = (bounds[1] + bounds[3]) / 2f
    val scale = 1f / (bounds[2] - bounds[0])
    return transformed { x, y -> TransformResult((x - centerX) * scale + 0.5f, (y - centerY) * scale + 0.5f) }
}

/**
 * Draws [morph] at [progress], [extent] across, centred on [center] and turned by [degrees]. [path]
 * is reused between frames so a morph drawn from a clock allocates nothing while it runs.
 */
fun DrawScope.drawMorph(
    morph: Morph,
    progress: Float,
    path: Path,
    center: Offset,
    extent: Float,
    degrees: Float,
    color: Color,
) {
    morph.toPath(progress, path)
    withTransform({
        translate(center.x - extent / 2f, center.y - extent / 2f)
        rotate(degrees, pivot = Offset(extent / 2f, extent / 2f))
        scale(extent, extent, pivot = Offset.Zero)
    }) { drawPath(path, color) }
}

/**
 * A portrait that pops in on a spring and morphs from a circle into the artist cookie as it lands.
 * With no [imageUrl], or while it loads, [fallback] initials sit on the accent colour.
 */
@Composable
fun FlowMorphingPortrait(
    imageUrl: String,
    fallback: String,
    diameter: Dp,
    accent: Color,
    onAccent: Color,
    modifier: Modifier = Modifier,
    delayIndex: Int = 0,
) {
    val pop = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(delayIndex * STAGGER_MS)
        pop.animateTo(1f, spec)
    }
    Box(
        modifier =
            modifier
                .size(diameter)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }.drawWithCache {
                    val path = Path()
                    onDrawWithContent {
                        PortraitMorph.toPath((pop.value / PORTRAIT_MORPH_SHARE).coerceIn(0f, 1f), path)
                        clipPath(path.scaledTo(size.width, size.height)) { this@onDrawWithContent.drawContent() }
                    }
                }.background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fallback.initials(),
            color = onAccent,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (imageUrl.isNotBlank()) {
            AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Content that springs in after [index] siblings, for lists that should land one by one. */
@Composable
fun FlowPopIn(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pop = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(index * STAGGER_MS)
        pop.animateTo(1f, spec)
    }
    Box(
        modifier.graphicsLayer {
            val value = pop.value
            scaleX = value
            scaleY = value
            alpha = value.coerceIn(0f, 1f)
        },
    ) { content() }
}

private fun Path.scaledTo(
    width: Float,
    height: Float,
): Path {
    transform(Matrix().apply { scale(width, height) })
    return this
}

/** The first letters of up to two words, for a portrait with no image. */
private fun String.initials(): String =
    split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
