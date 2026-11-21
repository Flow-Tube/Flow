package io.github.aedev.flow.ui.components.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.R

private const val CAPTION_TEXT_FRACTION = 0.075f

// Keeps captions off the screen edge on a full-width card.
private const val CAPTION_SIDE_PADDING_DP = 8

// Lifts captions clear of the time bar and the status badge along the bottom edge.
private const val CAPTION_BOTTOM_FRACTION = 0.2f

/**
 * The preview's video, drawn over a card's thumbnail. A TextureView surface, so it scrolls and
 * clips with the card; it stays transparent until [showVideo], which waits for a rendered frame,
 * so the thumbnail is what shows while the stream starts. [onAttached] reports whether a surface
 * exists to decode onto.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun HomePreviewSurface(
    player: Player?,
    showVideo: Boolean,
    onAttached: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (showVideo) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "previewSurfaceAlpha",
    )
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                subtitleView?.apply {
                    setUserDefaultStyle()
                    setFractionalTextSize(CAPTION_TEXT_FRACTION)
                    setBottomPaddingFraction(CAPTION_BOTTOM_FRACTION)
                    val sidePadding = (CAPTION_SIDE_PADDING_DP * resources.displayMetrics.density).toInt()
                    setPadding(sidePadding, 0, sidePadding, 0)
                }
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
        modifier = modifier.fillMaxSize().graphicsLayer { alpha = surfaceAlpha },
    )
    val currentOnAttached by rememberUpdatedState(onAttached)
    DisposableEffect(Unit) {
        currentOnAttached(true)
        onDispose { currentOnAttached(false) }
    }
}
