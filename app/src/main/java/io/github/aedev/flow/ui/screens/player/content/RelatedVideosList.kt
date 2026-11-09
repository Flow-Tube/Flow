package io.github.aedev.flow.ui.screens.player.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.PlayerRelatedCardStyle
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.partialRowIndices
import io.github.aedev.flow.ui.components.shared.LocalSkeletonGradientEnabled
import io.github.aedev.flow.ui.components.shared.ThumbnailSkeletonGradient
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.card.VideoCardSkeleton
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.delay

/**
 * Related videos content for LazyListScope.
 */
internal fun LazyListScope.relatedVideosContent(
    relatedVideos: List<Video>,
    onVideoClick: (Video) -> Unit,
    cardStyle: PlayerRelatedCardStyle = PlayerRelatedCardStyle.FULL_WIDTH,
) {
    // Video items
    items(
        count = relatedVideos.size,
        key = { index -> relatedVideos[index].id },
    ) { index ->
        val relatedVideo = relatedVideos[index]
        when (cardStyle) {
            PlayerRelatedCardStyle.COMPACT -> {
                MediaVideoCard(
                    video = relatedVideo,
                    layout = VideoCardLayout.Row,
                    onClick = { onVideoClick(relatedVideo) },
                )
            }

            PlayerRelatedCardStyle.FULL_WIDTH -> {
                MediaVideoCard(
                    video = relatedVideo,
                    onClick = { onVideoClick(relatedVideo) },
                )
            }
        }
    }
}

/**
 * The related list on a wide single-pane player: the shared feed grid's columns, chunked into rows
 * of a [androidx.compose.foundation.lazy.LazyColumn] so the video details above keep their own
 * padding. A row the grid cannot fill, and every row in the compact style, is a thumbnail-left card
 * whose thumbnail is one grid column wide.
 */
internal fun LazyListScope.relatedVideosGridContent(
    relatedVideos: List<Video>,
    layout: FeedGridLayout,
    onVideoClick: (Video) -> Unit,
    cardStyle: PlayerRelatedCardStyle = PlayerRelatedCardStyle.FULL_WIDTH,
) {
    val columns = if (cardStyle == PlayerRelatedCardStyle.COMPACT) 1 else layout.columns
    val rows = relatedGridRows(relatedVideos.size, columns)

    items(
        count = rows.size,
        key = { index -> rows[index].joinToString { relatedVideos[it].id } },
        contentType = { index -> if (rows[index].size == 1) "related_row" else "related_grid_row" },
    ) { index ->
        val row = rows[index]
        if (row.size == 1) {
            val video = relatedVideos[row.single()]
            MediaVideoCard(
                video = video,
                layout = VideoCardLayout.Row,
                onClick = { onVideoClick(video) },
                thumbnailWidth = layout.thumbnailWidth,
                density = layout.cardDensity,
                modifier = Modifier.padding(horizontal = layout.contentPadding),
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = layout.contentPadding,
                            vertical = if (layout.dense) layout.cardSpacing / 2 else GridRowVerticalPadding,
                        ),
            ) {
                row.forEach { videoIndex ->
                    val video = relatedVideos[videoIndex]
                    Box(modifier = Modifier.weight(1f)) {
                        MediaVideoCard(video = video, onClick = { onVideoClick(video) }, density = layout.cardDensity)
                    }
                }
            }
        }
    }
}

/**
 * Whether the related list lays out as [relatedVideosGridContent]: always on the medium layout, and on
 * a phone once Cards per row asks for more than one full-width card.
 */
internal fun relatedListUsesGrid(
    isMediumLayout: Boolean,
    cardStyle: PlayerRelatedCardStyle,
    layout: FeedGridLayout,
): Boolean = isMediumLayout || (cardStyle == PlayerRelatedCardStyle.FULL_WIDTH && layout.columns > 1)

/** Full rows of [columns] indices, then each index a full row cannot hold on a row of its own. */
internal fun relatedGridRows(
    count: Int,
    columns: Int,
): List<List<Int>> {
    val partial = partialRowIndices(List(count) { false }, columns)
    val full = (0 until count).filterNot { it in partial }.chunked(columns.coerceAtLeast(1))
    return full + partial.sorted().map(::listOf)
}

/**
 * Whether the related list should hold placeholders: only with the gradient setting on, because
 * the page has none otherwise, and only on the open player, because the page stays composed behind
 * the mini player and a pulse nobody can see costs frames for nothing.
 */
internal fun relatedSkeletonWanted(
    gradientEnabled: Boolean,
    playerExpanded: Boolean,
    relatedEmpty: Boolean,
    hasError: Boolean,
): Boolean = gradientEnabled && playerExpanded && relatedEmpty && !hasError

/**
 * True while [video] loads, and for a moment after, since the related list can land just behind
 * it. With the setting off this holds no state and starts no timer.
 */
@Composable
internal fun rememberRelatedSkeleton(
    video: Video,
    uiState: VideoPlayerUiState,
    screenState: PlayerScreenState,
): Boolean {
    val wanted =
        relatedSkeletonWanted(
            gradientEnabled = LocalSkeletonGradientEnabled.current,
            playerExpanded = screenState.isPlayerExpanded,
            relatedEmpty = uiState.relatedVideos.isEmpty(),
            hasError = uiState.error != null,
        )
    if (!wanted) return false
    var visible by remember(video.id) { mutableStateOf(uiState.isLoading) }
    LaunchedEffect(video.id, uiState.isLoading) {
        if (uiState.isLoading) {
            visible = true
        } else if (visible) {
            delay(RELATED_SKELETON_HOLD_MS)
            visible = false
        }
    }
    return visible
}

/** Placeholders for the related list, tinted from the playing video's thumbnail. */
internal fun LazyListScope.relatedVideosSkeleton(
    thumbnailUrl: String,
    cardStyle: PlayerRelatedCardStyle,
) {
    item(key = "related_skeleton", contentType = "related_skeleton") {
        ThumbnailSkeletonGradient(thumbnailUrl) {
            Column(verticalArrangement = Arrangement.spacedBy(SkeletonCardSpacing)) {
                repeat(RELATED_SKELETON_CARDS) {
                    VideoCardSkeleton(
                        layout = if (cardStyle == PlayerRelatedCardStyle.COMPACT) VideoCardLayout.Row else VideoCardLayout.Stacked,
                    )
                }
            }
        }
    }
}

private val GridRowVerticalPadding = 6.dp
private val SkeletonCardSpacing = 12.dp
private const val RELATED_SKELETON_CARDS = 3
private const val RELATED_SKELETON_HOLD_MS = 3_000L
