@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.aedev.flow.ui.components.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.data.model.hasLikelyCollaborationByline
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.repository.VideoCollaboratorResolver
import io.github.aedev.flow.ui.components.shared.MediaTextBadge
import io.github.aedev.flow.ui.components.shared.VideoThumbnailImage
import io.github.aedev.flow.ui.components.shared.WatchProgressBar
import io.github.aedev.flow.ui.components.shared.card.rememberCollaboratorChannelDisplayName
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.components.shared.dpadReturnFocus
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.onMenuKey
import io.github.aedev.flow.ui.components.shared.pressScale
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.ui.components.shared.skipDpadFocus
import io.github.aedev.flow.ui.components.shared.thumbnailGradientOverlay

object ContinueWatchingShelfDefaults {
    val CardWidth: Dp = 350.dp
}

/** [cardWidth] comes from the width the strip scrolls across, so a tablet does not pin phone-sized cards. */
@Composable
fun ContinueWatchingShelf(
    entries: List<VideoHistoryEntry>,
    onVideoClick: (String) -> Unit,
    onRemove: (String) -> Unit = {},
    onSeeAllClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    cardWidth: Dp = ContinueWatchingShelfDefaults.CardWidth,
) {
    val uniqueEntries =
        remember(entries) {
            entries.distinctByNonBlankKey(VideoHistoryEntry::videoId)
        }
    if (uniqueEntries.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (onSeeAllClick != null) Modifier.clickable(onClick = onSeeAllClick) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.continue_watching_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (onSeeAllClick != null) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        LazyRow(
            modifier = Modifier.dpadFocusGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(uniqueEntries, key = { it.videoId }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    cardWidth = cardWidth,
                    onClick = { onVideoClick(entry.videoId) },
                    onRemove = { onRemove(entry.videoId) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: VideoHistoryEntry,
    cardWidth: Dp,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val resolvedCollaborators by produceState<List<VideoCollaborator>>(
        initialValue = emptyList(),
        key1 = entry.videoId,
        key2 = entry.channelName,
    ) {
        value =
            if (entry.channelName.hasLikelyCollaborationByline()) {
                VideoCollaboratorResolver.resolve(entry.videoId)
            } else {
                emptyList()
            }
    }
    val displayChannelName = rememberCollaboratorChannelDisplayName(entry.channelName, resolvedCollaborators)
    val removeLabel = stringResource(R.string.remove_from_history)
    var showMenu by remember { mutableStateOf(false) }

    ShelfVideoCardContent(
        videoId = entry.videoId,
        thumbnailUrl = entry.thumbnailUrl,
        title = entry.title,
        channelName = displayChannelName,
        durationText =
            entry.duration.takeIf { it > 0 }?.let { duration ->
                formatContinueWatchingTime((duration - entry.position).coerceAtLeast(0L))
            },
        progress = (entry.progressPercentage / 100f).coerceIn(0f, 1f),
        width = cardWidth,
        onClick = onClick,
        onLongClick = { showMenu = true },
        trailingContent = {
            IconButton(onClick = onRemove, modifier = Modifier.skipDpadFocus()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = removeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
    if (showMenu) {
        VideoQuickActionsBottomSheet(
            video = remember(entry) { entry.toVideo() },
            onDismiss = { showMenu = false },
            onRemoveFromCollection = onRemove,
            removeFromCollectionLabel = removeLabel,
        )
    }
}

@Composable
private fun ShelfVideoCardContent(
    videoId: String,
    thumbnailUrl: String,
    title: String,
    channelName: String,
    durationText: String?,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .width(width)
                .pressScale(interactionSource)
                .focusLift(interactionSource)
                .onMenuKey(onLongClick)
                .dpadReturnFocus(videoId)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.material3.ripple(focusRingShape = MaterialTheme.shapes.medium),
                    onLongClickLabel = stringResource(R.string.more_options),
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailGradientOverlay(),
        ) {
            VideoThumbnailImage(
                videoId = videoId,
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (durationText != null) {
                MediaTextBadge(
                    text = durationText,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                )
            }
            if (progress != null) {
                WatchProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.12f,
                        ),
                    fontWeight = FontWeight.SemiBold,
                )
                if (channelName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

private fun formatContinueWatchingTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
