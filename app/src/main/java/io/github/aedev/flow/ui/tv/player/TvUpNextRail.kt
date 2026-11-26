package io.github.aedev.flow.ui.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.tv.focus.ProvideTvRowPivot

/** Three cards across and the edge of a fourth, so the row reads as one that scrolls on. */
private const val UP_NEXT_CARD_FRACTION = 0.31f

private const val THUMBNAIL_ASPECT = 16f / 9f

private val UpNextCardSpacing = 16.dp
private val UpNextPanelPadding = 16.dp

/** What the row shows: the rest of the queue, then the related videos that aren't already in it. */
internal data class UpNextRows(
    val upcoming: List<Video>,
    val related: List<Video>,
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && related.isEmpty()
}

internal fun upNextRows(
    queue: List<Video>,
    currentQueueIndex: Int,
    relatedVideos: List<Video>,
): UpNextRows {
    val upcoming = queue.drop(currentQueueIndex + 1)
    val queued = queue.mapTo(HashSet()) { it.id }
    return UpNextRows(upcoming, relatedVideos.filter { it.id !in queued })
}

/**
 * The row under the player controls: the rest of the queue when one is playing, a divider, then the
 * related videos, as the phone's cards, on a surface panel of their own.
 */
@Composable
internal fun TvUpNextRail(
    queue: List<Video>,
    currentQueueIndex: Int,
    relatedVideos: List<Video>,
    onVideoClick: (Video) -> Unit,
    firstItemFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val rows = upNextRows(queue, currentQueueIndex, relatedVideos)
    if (rows.isEmpty) return

    // A solid panel, so the cards and their text stand out over any frame (#998).
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(UpNextPanelPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(if (rows.upcoming.isNotEmpty()) R.string.tv_player_queue else R.string.up_next),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints {
                val thumbnailHeight = maxWidth * UP_NEXT_CARD_FRACTION / THUMBNAIL_ASPECT
                ProvideTvRowPivot {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().dpadFocusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(UpNextCardSpacing),
                    ) {
                        cards(rows.upcoming, "queue", onVideoClick, firstItemFocusRequester)
                        if (rows.upcoming.isNotEmpty() && rows.related.isNotEmpty()) {
                            item(key = "divider") { VerticalDivider(Modifier.height(thumbnailHeight)) }
                        }
                        cards(rows.related, "related", onVideoClick, firstItemFocusRequester.takeIf { rows.upcoming.isEmpty() })
                    }
                }
            }
        }
    }
}

private fun LazyListScope.cards(
    videos: List<Video>,
    section: String,
    onVideoClick: (Video) -> Unit,
    firstItemFocusRequester: FocusRequester?,
) {
    itemsIndexed(videos, key = { index, video -> "${section}_${video.id}_$index" }) { index, video ->
        MediaVideoCard(
            video = video,
            onClick = { onVideoClick(video) },
            useInternalPadding = false,
            modifier =
                Modifier
                    .fillParentMaxWidth(UP_NEXT_CARD_FRACTION)
                    .then(
                        if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                    ),
        )
    }
}
