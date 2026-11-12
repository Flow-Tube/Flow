package io.github.aedev.flow.ui.components.videoplayer.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.DoubleTapSeekZone
import io.github.aedev.flow.ui.theme.PlayerScrimContent

private const val SEEK_RIPPLE_ALPHA = 0.15f
private const val SEEK_RIPPLE_PULSE_ALPHA = 0.28f
private val ChevronTravel = 24.dp
private val ChevronSize = 26.dp
private val ChapterTitleMaxWidth = 200.dp

/** A two-finger double tap that just moved to another chapter. Each skip is a new instance. */
internal class ChapterSkipFeedback(
    val title: String,
    val forward: Boolean,
)

@Composable
internal fun SeekAnimationOverlay(
    showSeekBack: Boolean,
    showSeekForward: Boolean,
    seekSeconds: Int = 10,
    zoneFraction: Float = DoubleTapSeekZone.NORMAL.sideFraction,
    chapterSkip: ChapterSkipFeedback? = null,
    showChapterSkip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val chapterBack = showChapterSkip && chapterSkip?.forward == false
    val chapterForward = showChapterSkip && chapterSkip?.forward == true
    val pulseKey = if (showChapterSkip && chapterSkip != null) chapterSkip.hashCode() else seekSeconds

    Box(modifier = modifier.fillMaxSize()) {
        SeekZoneRipple(
            visible = showSeekBack || chapterBack,
            forward = false,
            pulseKey = pulseKey,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(zoneFraction),
        )

        SeekZoneRipple(
            visible = showSeekForward || chapterForward,
            forward = true,
            pulseKey = pulseKey,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(zoneFraction),
        )

        AnimatedVisibility(
            visible = showSeekBack,
            enter = fadeIn(fadeSpec),
            // Exit instantly when switching to forward (no overlap), otherwise fade normally.
            exit = if (showSeekForward) fadeOut(snap()) else fadeOut(fadeSpec),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp),
        ) {
            SeekChevronLabel(forward = false, seconds = seekSeconds)
        }

        AnimatedVisibility(
            visible = showSeekForward,
            enter = fadeIn(fadeSpec),
            // Exit instantly when switching to backward (no overlap), otherwise fade normally.
            exit = if (showSeekBack) fadeOut(snap()) else fadeOut(fadeSpec),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp),
        ) {
            SeekChevronLabel(forward = true, seconds = seekSeconds)
        }

        AnimatedVisibility(
            visible = chapterBack,
            enter = fadeIn(fadeSpec),
            exit = fadeOut(fadeSpec),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp),
        ) {
            SeekChevronLabel(forward = false, text = chapterSkip?.title.orEmpty())
        }

        AnimatedVisibility(
            visible = chapterForward,
            enter = fadeIn(fadeSpec),
            exit = fadeOut(fadeSpec),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp),
        ) {
            SeekChevronLabel(forward = true, text = chapterSkip?.title.orEmpty())
        }
    }
}

@Composable
private fun SeekChevronLabel(
    forward: Boolean,
    seconds: Int,
) {
    SeekChevronLabel(
        forward = forward,
        text =
            stringResource(
                if (forward) R.string.player_seek_seconds_forward else R.string.player_seek_seconds_back,
                seconds,
            ),
    )
}

/**
 * The tinted zone that flashes behind a double-tap seek.
 *
 * Drawn as an oversized circle clipped to the zone so the outer edge sits flush against the screen
 * while the inner edge bulges — the shape reads as "this side of the player reacted" without any
 * shadow or glow. The animated alpha is read inside `graphicsLayer`, so repeated taps repaint
 * without recomposing anything.
 */
@Composable
private fun SeekZoneRipple(
    visible: Boolean,
    forward: Boolean,
    pulseKey: Int,
    modifier: Modifier = Modifier,
) {
    val rippleAlpha = remember { Animatable(0f) }
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    LaunchedEffect(visible, pulseKey) {
        if (visible) {
            rippleAlpha.snapTo(SEEK_RIPPLE_PULSE_ALPHA)
            rippleAlpha.animateTo(SEEK_RIPPLE_ALPHA, fadeSpec)
        } else {
            rippleAlpha.animateTo(0f, fadeSpec)
        }
    }

    Canvas(
        modifier = modifier.graphicsLayer { alpha = rippleAlpha.value },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Smallest radius whose circle still covers both corners on the flush edge, so the shape
        // never leaves a sliver of untinted video at the screen border.
        val radius = w / 2f + (h * h) / (8f * w)
        val centerX = if (forward) radius else w - radius

        clipRect(left = 0f, top = 0f, right = w, bottom = h) {
            drawCircle(
                color = PlayerScrimContent,
                radius = radius,
                center = Offset(centerX, h / 2f),
            )
        }
    }
}

/**
 * The chevrons that chase the seek direction.
 *
 * The transition lives inside the `AnimatedVisibility` content, so it is cancelled with the label
 * once the seek indicator leaves; while it runs, its travel and alpha are applied in
 * `graphicsLayer` so the 800ms loop repaints without recomposing or re-laying out the row.
 */
@Composable
private fun SeekChevronLabel(
    forward: Boolean,
    text: String,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "chevron")

    val progress =
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "chevronProgress",
        )

    val chevronModifier =
        Modifier.graphicsLayer {
            val value = progress.value
            val travel = ChevronTravel.toPx() * LinearOutSlowInEasing.transform(value)
            translationX = if (forward) travel else -travel
            alpha =
                when {
                    value < 0.2f -> value * 5f
                    value > 0.5f -> (1f - value) * 2f
                    else -> 1f
                }.coerceIn(0f, 1f)
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!forward) {
            SeekChevron(forward = false, modifier = chevronModifier)
        }
        Text(
            text = text,
            color = PlayerScrimContent,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = ChapterTitleMaxWidth),
        )
        if (forward) {
            SeekChevron(forward = true, modifier = chevronModifier)
        }
    }
}

@Composable
private fun SeekChevron(
    forward: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector =
            if (forward) {
                Icons.Rounded.KeyboardDoubleArrowRight
            } else {
                Icons.Rounded.KeyboardDoubleArrowLeft
            },
        contentDescription = null,
        tint = PlayerScrimContent,
        modifier = modifier.size(ChevronSize),
    )
}
