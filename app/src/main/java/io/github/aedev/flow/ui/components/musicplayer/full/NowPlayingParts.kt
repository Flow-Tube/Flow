package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerMainActionButtons
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerSecondaryActions
import io.github.aedev.flow.ui.components.shared.LocalMarqueeEnabled

internal val PlayerHorizontalPadding = 28.dp

/**
 * The pieces of the now-playing surface. Each layout decides where they go; the pieces never know
 * which layout they are in. Every slot takes the modifier the layout sizes and places it with.
 */
@Immutable
internal class NowPlayingSlots(
    val topBar: @Composable (Modifier) -> Unit,
    val artwork: @Composable (Modifier) -> Unit,
    val header: @Composable (Modifier) -> Unit,
    val progress: @Composable (Modifier) -> Unit,
    val controls: @Composable (Modifier) -> Unit,
    val actions: @Composable (Modifier) -> Unit,
)

/** The cover with its elevation; under the immersive background the full-bleed art is the cover. */
@Composable
internal fun PlayerArtworkFrame(
    immersive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .then(
                    if (immersive) {
                        Modifier
                    } else {
                        Modifier.shadow(
                            elevation = if (isPlaying) 24.dp else 8.dp,
                            shape = RoundedCornerShape(8.dp),
                        )
                    },
                ).clip(RoundedCornerShape(8.dp)),
    ) {
        content()
    }
}

@Composable
internal fun PlayerTrackHeader(
    title: String,
    artist: String,
    onArtistClick: () -> Unit,
    animateTrackText: Boolean,
    showLibraryActions: Boolean,
    isLiked: Boolean,
    isDownloaded: Boolean,
    onLikeClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "title",
            ) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackTextMarquee(animateTrackText),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            AnimatedContent(
                targetState = artist,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "artist",
            ) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onArtistClick).trackTextMarquee(animateTrackText),
                )
            }
        }

        if (showLibraryActions) {
            Spacer(modifier = Modifier.width(12.dp))
            PlayerMainActionButtons(
                isLiked = isLiked,
                isDownloaded = isDownloaded,
                onLikeClick = onLikeClick,
                onDownloadClick = onDownloadClick,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

/** Keeps looping so a long name can be read in full. The caller stops it whenever nobody can see it. */
@Composable
private fun Modifier.trackTextMarquee(enabled: Boolean): Modifier =
    if (enabled && LocalMarqueeEnabled.current) {
        basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = 10_000,
            initialDelayMillis = 3000,
            velocity = 30.dp,
        )
    } else {
        this
    }

@Composable
internal fun PlayerActionRow(
    lyricsActive: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onLyricsClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onQueueClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    queueActive: Boolean = false,
    sleepTimerActive: Boolean = false,
    onSleepTimerClick: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerSecondaryActions(
            lyricsActive = lyricsActive,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            onLyricsClick = onLyricsClick,
            onShuffleClick = onShuffleClick,
            onRepeatClick = onRepeatClick,
            onQueueClick = onQueueClick,
            modifier = Modifier.weight(1f),
            queueActive = queueActive,
            sleepTimerActive = sleepTimerActive,
            onSleepTimerClick = onSleepTimerClick,
        )
        FilledTonalIconButton(
            onClick = onMoreClick,
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colorScheme.secondaryContainer,
                    contentColor = colorScheme.onSecondaryContainer,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
