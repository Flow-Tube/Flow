package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.MediaIconBadge
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.VideoStatusBadge

/**
 * A card's thumbnail: the shared [MediaThumbnail] with the status, reminder and DeArrow badges on it.
 * The image is decorative because the card's title already names the video.
 *
 * [width] is unspecified for a stacked card, whose [modifier] fills the width instead.
 */
@Composable
internal fun VideoCardThumbnail(
    state: VideoCardState,
    isUpcoming: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
) {
    val video = state.video
    // DeArrow answers 204 for a frame it has not rendered yet, and the image falls back to YouTube's
    // own frame, never the uploader's thumbnail.
    var deArrowFrameShown by remember(state.deArrowFrameUrl) { mutableStateOf(false) }
    MediaThumbnail(
        videoId = video.id,
        thumbnailUrl = state.thumbnailUrl,
        width = width,
        shape = shape,
        showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
        onModelLoaded = state.deArrowFrameUrl?.let { frame -> { loaded -> deArrowFrameShown = loaded == frame } },
        youTubeFrames = state.youTubeFrames,
        modifier = modifier,
    ) {
        val badgeModifier = Modifier.padding(MediaThumbnailDefaults.BadgePadding)
        VideoStatusBadge(
            isLive = video.isLive,
            isUpcoming = isUpcoming,
            durationSeconds = video.duration,
            modifier = badgeModifier.align(Alignment.BottomEnd),
        )
        if (state.showReminderBadge) {
            MediaIconBadge(
                icon = Icons.Rounded.NotificationsActive,
                contentDescription = stringResource(R.string.upcoming_video_reminder_badge),
                modifier = badgeModifier.align(Alignment.TopStart),
            )
        }
        if (state.deArrowBadgeEnabled && (state.deArrowTitle || deArrowFrameShown)) {
            MediaIconBadge(
                icon = Icons.Outlined.AutoFixHigh,
                contentDescription = stringResource(R.string.dearrow_badge),
                modifier = badgeModifier.align(Alignment.TopEnd),
            )
        }
    }
}
