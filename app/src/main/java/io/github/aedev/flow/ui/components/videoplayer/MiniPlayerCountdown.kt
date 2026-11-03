package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.player.AutoplayCountdownState
import io.github.aedev.flow.ui.components.shared.thumbnailUrlOrNull
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentDisabled
import io.github.aedev.flow.ui.theme.PlayerScrimMiniTopButton
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isMediumWidth

/**
 * The autoplay countdown as the mini player shows it, in place of the mini controls: the next
 * video's picture, a ring that fills up to Play next, and Cancel where Close sits. Taps outside the
 * two buttons fall through to the mini window, which still opens the full player.
 *
 * Only composed while a countdown runs, so the ring's once-a-second retarget never outlives it.
 */
@Composable
internal fun MiniPlayerCountdown(
    state: AutoplayCountdownState,
    sizeScale: Float,
    onPlayNext: () -> Unit,
    onCancel: () -> Unit,
) {
    val isWideWindow = LocalWindowSizeClass.current.isMediumWidth
    val scaleMult = sizeScale.coerceIn(1f, 1.6f)
    val ringSize = (if (isWideWindow) 52.dp else 44.dp) * scaleMult
    val topTouchSize = if (isWideWindow) 50.dp else 42.dp
    val topBgSize = if (isWideWindow) 42.dp else 34.dp
    val elapsed by animateFloatAsState(
        targetValue =
            if (state.totalSeconds > 0) {
                1f - state.secondsRemaining.toFloat() / state.totalSeconds.toFloat()
            } else {
                1f
            },
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "miniAutoplayCountdown",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        thumbnailUrlOrNull(state.nextVideoThumbnailUrl)?.let { thumb ->
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(PlayerScrimPanel),
        )

        Text(
            text = stringResource(R.string.autoplay_countdown_mini_label, state.secondsRemaining.coerceAtLeast(0)),
            style = MaterialTheme.typography.labelMedium,
            color = PlayerScrimContent,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )

        IconButton(
            onClick = onCancel,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(topTouchSize),
        ) {
            MiniPlayerButtonBackground(
                backgroundSize = topBgSize,
                backgroundColor = PlayerScrimMiniTopButton,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.autoplay_countdown_cancel),
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(if (isWideWindow) 34.dp else 30.dp),
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(ringSize),
        ) {
            CircularProgressIndicator(
                progress = { elapsed },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = PlayerScrimContentDisabled,
            )
            IconButton(onClick = onPlayNext, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.autoplay_countdown_play_now),
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(ringSize * 0.55f),
                )
            }
        }

        Text(
            text =
                state.nextVideoTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.autoplay_countdown_next_video),
            style = MaterialTheme.typography.labelMedium,
            color = PlayerScrimContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
    }
}
