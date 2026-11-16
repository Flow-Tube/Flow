package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A loading placeholder that pulses between two surface tones. The pulse is read only while drawing,
 * so a skeleton costs one draw pass per frame and never recomposes; [delayMillis] staggers the bones of
 * one placeholder so they ripple instead of blinking together. With the user's gradient setting on, it
 * pulses between two gradients instead, whose brushes are built once per size.
 */
@Composable
fun Modifier.shimmerEffect(
    shape: Shape = MaterialTheme.shapes.small,
    durationMillis: Int = 1200,
    delayMillis: Int = 0,
): Modifier {
    val pulse =
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delayMillis),
                ),
            label = "skeleton_pulse",
        )
    if (LocalSkeletonGradientEnabled.current) {
        val gradient = LocalSkeletonGradient.current ?: themeSkeletonGradient()
        return this.clip(shape).drawWithCache {
            fun brush(
                start: Color,
                end: Color,
            ) = Brush.horizontalGradient(if (layoutDirection == LayoutDirection.Rtl) listOf(end, start) else listOf(start, end))
            val dim = brush(gradient.dimStart, gradient.dimEnd)
            val bright = brush(gradient.brightStart, gradient.brightEnd)
            onDrawBehind {
                drawRect(dim)
                drawRect(bright, alpha = pulse.value)
            }
        }
    }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .clip(shape)
        .drawBehind { drawRect(lerp(base, highlight, pulse.value)) }
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

@Composable
fun ShimmerSectionTitle(modifier: Modifier = Modifier) {
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

@Composable
fun ShimmerChipRow(
    modifier: Modifier = Modifier,
    chipCount: Int = 5,
) {
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
