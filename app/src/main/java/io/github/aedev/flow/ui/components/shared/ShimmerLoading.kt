package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.LoadingAnimation

private const val PULSE_GAIN = 2f
private const val SHIMMER_GAIN = 2.5f
private const val SHIMMER_PERIOD_MILLIS = 1500

// The band leans 20 degrees off vertical.
private const val SHIMMER_COS = 0.9397f
private const val SHIMMER_SIN = 0.3420f
private val ShimmerHalfWidth = 96.dp

/**
 * A loading placeholder that moves as the user's loading animation setting asks: a pulse between two
 * surface tones (wider by default, today's narrow one as Subtle pulse), or a band of light sweeping
 * across. The motion comes from the enclosing [SkeletonSurface]'s clock and is read only while drawing,
 * so a skeleton costs one draw pass per frame and never recomposes; [delayMillis] staggers the pulse of
 * one placeholder's bones so they ripple. With the gradient setting on, the same motion runs between
 * two gradients, whose brushes are built once per size.
 */
@Composable
fun Modifier.shimmerEffect(
    shape: Shape = MaterialTheme.shapes.small,
    durationMillis: Int = 1200,
    delayMillis: Int = 0,
): Modifier {
    val clock = currentSkeletonClock()
    val animation = LocalLoadingAnimation.current
    val gradient = if (LocalSkeletonGradientEnabled.current) LocalSkeletonGradient.current ?: themeSkeletonGradient() else null
    val high = MaterialTheme.colorScheme.surfaceContainerHigh
    val highest = MaterialTheme.colorScheme.surfaceContainerHighest
    val tones =
        remember(gradient, high, highest, animation) {
            skeletonTones(gradient ?: SkeletonGradient(high, high, highest, highest), animation)
        }
    if (animation == LoadingAnimation.SHIMMER) return shimmerSweep(shape, tones, clock)
    if (gradient == null) {
        return this
            .clip(shape)
            .drawBehind { drawRect(lerp(tones.dimStart, tones.brightStart, clock.pulse(durationMillis, delayMillis))) }
    }
    return this.clip(shape).drawWithCache {
        val dim = horizontalBrush(tones.dimStart, tones.dimEnd, layoutDirection)
        val bright = horizontalBrush(tones.brightStart, tones.brightEnd, layoutDirection)
        onDrawBehind {
            drawRect(dim)
            drawRect(bright, alpha = clock.pulse(durationMillis, delayMillis))
        }
    }
}

/** [base] with its bright pair pushed as far as [animation] swings. */
internal fun skeletonTones(
    base: SkeletonGradient,
    animation: LoadingAnimation,
): SkeletonGradient {
    val gain =
        when (animation) {
            LoadingAnimation.SUBTLE_PULSE -> return base
            LoadingAnimation.PULSE -> PULSE_GAIN
            LoadingAnimation.SHIMMER -> SHIMMER_GAIN
        }
    return base.copy(
        brightStart = stretchTone(base.dimStart, base.brightStart, gain),
        brightEnd = stretchTone(base.dimEnd, base.brightEnd, gain),
    )
}

private fun horizontalBrush(
    start: Color,
    end: Color,
    direction: LayoutDirection,
): Brush = Brush.horizontalGradient(if (direction == LayoutDirection.Rtl) listOf(end, start) else listOf(start, end))

/** Where a bone sits in the window, so every bone draws its slice of one band crossing the screen. */
private class SweepAnchor {
    var x = 0f
    var y = 0f
    var span = 0f
}

@Composable
private fun Modifier.shimmerSweep(
    shape: Shape,
    tones: SkeletonGradient,
    clock: SkeletonClock,
): Modifier {
    val anchor = remember { SweepAnchor() }
    return this
        .onPlaced { coordinates ->
            val position = coordinates.positionInRoot()
            val root = coordinates.findRootCoordinates().size
            anchor.x = position.x
            anchor.y = position.y
            anchor.span = root.width * SHIMMER_COS + root.height * SHIMMER_SIN
        }.clip(shape)
        .drawWithCache {
            val halfWidth = ShimmerHalfWidth.toPx()
            val base = horizontalBrush(tones.dimStart, tones.dimEnd, layoutDirection)
            val peak = lerp(tones.brightStart, tones.brightEnd, 0.5f)
            val band =
                Brush.linearGradient(
                    colors = listOf(peak.copy(alpha = 0f), peak, peak.copy(alpha = 0f)),
                    start = Offset(-halfWidth * SHIMMER_COS, -halfWidth * SHIMMER_SIN),
                    end = Offset(halfWidth * SHIMMER_COS, halfWidth * SHIMMER_SIN),
                )
            onDrawBehind {
                drawRect(base)
                val sweep = clock.sweep(SHIMMER_PERIOD_MILLIS)
                val fraction = if (layoutDirection == LayoutDirection.Rtl) 1f - sweep else sweep
                val centre = skeletonSweepCentre(fraction, anchor.span, halfWidth)
                val dx = centre * SHIMMER_COS - anchor.x
                val dy = centre * SHIMMER_SIN - anchor.y
                translate(dx, dy) { drawRect(band, topLeft = Offset(-dx, -dy), size = size) }
            }
        }
}

@Composable
fun ShimmerBone(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    delayMillis: Int = 0,
) {
    Box(
        modifier =
            modifier
                .shimmerEffect(shape = shape, delayMillis = delayMillis),
    )
}

@Composable
fun ShimmerGridItem(
    modifier: Modifier = Modifier,
    thumbnailAspectRatio: Float = 1f,
) {
    SkeletonSurface {
        Column(
            modifier = modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Thumbnail
            ShimmerBone(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(thumbnailAspectRatio),
                shape = MaterialTheme.shapes.medium,
            )

            // Title
            ShimmerBone(
                modifier =
                    Modifier
                        .fillMaxWidth(0.85f)
                        .height(13.dp),
                delayMillis = 80,
            )

            // Subtitle
            ShimmerBone(
                modifier =
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(11.dp),
                shape = MaterialTheme.shapes.extraSmall,
                delayMillis = 140,
            )
        }
    }
}

@Composable
fun ShimmerSectionTitle(modifier: Modifier = Modifier) {
    SkeletonSurface {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBone(
                modifier =
                    Modifier
                        .width(130.dp)
                        .height(18.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )

            ShimmerBone(
                modifier =
                    Modifier
                        .width(50.dp)
                        .height(14.dp),
                shape = MaterialTheme.shapes.extraSmall,
                delayMillis = 100,
            )
        }
    }
}

@Composable
fun ShimmerChipRow(
    modifier: Modifier = Modifier,
    chipCount: Int = 5,
) {
    SkeletonSurface {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(chipCount) { index ->
                ShimmerBone(
                    modifier =
                        Modifier
                            .width((60 + (index * 12) % 40).dp)
                            .height(32.dp),
                    shape = MaterialTheme.shapes.large,
                    delayMillis = index * 60,
                )
            }
        }
    }
}

@Composable
fun ShimmerMoodButton(modifier: Modifier = Modifier) {
    ShimmerBone(
        modifier =
            modifier
                .height(48.dp)
                .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    )
}

/**
 * Shimmer that mirrors the exact Music screen layout:
 *  - Filter chips row
 *  - "Quick picks" two-column grid (left album art + text + right small album art)
 *  - "Recommended" horizontal card row
 *  - "Recently played" horizontal card row
 */
@Composable
fun MusicScreenShimmerLoading(modifier: Modifier = Modifier) {
    SkeletonSurface {
        Column(modifier = modifier.fillMaxSize()) {
            // Filter chips (Workout, Energize, Relax…)
            ShimmerChipRow(chipCount = 5)

            Spacer(Modifier.height(8.dp))

            // ── Quick picks ────────────────────────────────────────────────────
            ShimmerSectionTitle()

            // 4 rows that mimic [left thumb | title+artist | right small thumb]
            repeat(4) { index ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left album art square
                    ShimmerBone(
                        modifier = Modifier.size(56.dp),
                        shape = MaterialTheme.shapes.small,
                        delayMillis = index * 40,
                    )

                    // Title + artist stacked
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.80f).height(13.dp),
                            delayMillis = 60 + index * 40,
                        )
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.50f).height(11.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            delayMillis = 100 + index * 40,
                        )
                    }

                    // Right small thumbnail
                    ShimmerBone(
                        modifier = Modifier.size(56.dp),
                        shape = MaterialTheme.shapes.small,
                        delayMillis = 120 + index * 40,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Recommended ────────────────────────────────────────────────────
            ShimmerSectionTitle()

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) { index ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Square album art
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                            delayMillis = index * 60,
                        )
                        // Title
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.90f).height(12.dp),
                            delayMillis = 40 + index * 60,
                        )
                        // Artist
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.65f).height(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            delayMillis = 80 + index * 60,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Recently played ─────────────────────────────────────────────────
            ShimmerSectionTitle()

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(3) { index ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            shape = MaterialTheme.shapes.medium,
                            delayMillis = index * 50,
                        )
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.85f).height(12.dp),
                            delayMillis = 40 + index * 50,
                        )
                        ShimmerBone(
                            modifier = Modifier.fillMaxWidth(0.60f).height(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            delayMillis = 80 + index * 50,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        content = content,
    )
}
