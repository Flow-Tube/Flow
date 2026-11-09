package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.videoMetadataLine

/** Thumbnail on the left, details on the right: lists, side panes and search rows. */
@Composable
internal fun VideoCardRow(
    video: Video,
    onClick: () -> Unit,
    showChannel: Boolean,
    thumbnailWidth: Dp,
    inset: Dp,
    reason: String?,
    whyShown: String?,
    modifier: Modifier = Modifier,
) {
    val state = rememberVideoCardState(video)
    val cardPreferences = LocalVideoCardPreferences.current
    val actions = LocalVideoCardActions.current
    // A negative count is the older "no count reported" sentinel; a row that declares itself
    // upcoming counts too. The badge and the metadata line read the same answer.
    val isUpcomingRow = video.isUpcoming || video.viewCount < 0L

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .videoCardClickable(state, onClick)
                .padding(
                    start = inset,
                    top = VideoCardDefaults.RowVerticalPadding,
                    bottom = VideoCardDefaults.RowVerticalPadding,
                ),
    ) {
        VideoCardThumbnail(
            state = state,
            isUpcoming = isUpcomingRow,
            shape = MaterialTheme.shapes.medium,
            width = thumbnailWidth,
        )

        Spacer(modifier = Modifier.width(VideoCardDefaults.Inset))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (showChannel) {
                Text(
                    text = state.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.videoCardChannelClickable(state),
                )
            }

            Text(
                text =
                    videoMetadataLine(
                        video = video,
                        isUpcoming = isUpcomingRow,
                        channelName = state.channelName,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isUpcomingRow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            MembersOnlyLabel(video)
            reason?.let { CardReasonLabel(it) }
        }

        VideoCardSideActions(
            state = state,
            showWatched = cardPreferences.markWatchedEnabled,
            onWatched = actions.onWatched,
            alignTo = 14.dp,
        )
    }

    VideoCardSheets(state = state, showChannel = showChannel, whyShown = whyShown)
}
