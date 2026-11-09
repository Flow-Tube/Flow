package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.ChannelAvatarStack
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.videoMetadataLine

/**
 * The thumbnail across the full width with the details beneath: feeds and grids. A dense card has no
 * room for the feedback buttons either; the long-press menu holds the same three actions.
 */
@Composable
internal fun VideoCardStacked(
    video: Video,
    onClick: () -> Unit,
    showChannel: Boolean,
    useInternalPadding: Boolean,
    density: VideoCardDensity,
    reason: String?,
    whyShown: String?,
    modifier: Modifier = Modifier,
) {
    val state = rememberVideoCardState(video)
    val cardPreferences = LocalVideoCardPreferences.current
    val actions = LocalVideoCardActions.current
    val dense = density == VideoCardDensity.Dense

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .videoCardClickable(state, onClick)
                .then(if (useInternalPadding) Modifier.padding(horizontal = density.inset) else Modifier),
    ) {
        VideoCardThumbnail(
            state = state,
            isUpcoming = video.isUpcoming,
            shape = if (dense) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )

        if (dense) {
            StackedCardText(
                video = video,
                state = state,
                showChannel = showChannel,
                reason = reason,
                dense = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = DenseDetailsTopPadding, bottom = DenseDetailsBottomPadding),
            )
        } else {
            // The avatar's 48 dp target overhangs its 40 dp image by 4 dp, so the row gives those 4 dp back
            // and the avatar still lines up where it always has.
            CardDetailsRow(
                spacing = if (showChannel) 8.dp else 12.dp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (showChannel) 8.dp else 12.dp, top = 12.dp, bottom = 12.dp),
                avatar = {
                    if (showChannel) {
                        ChannelAvatarStack(
                            urls = state.avatarUrls,
                            contentDescription = state.channelName,
                            avatarSize = VideoCardDefaults.AvatarSize,
                            modifier =
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(CircleShape)
                                    .videoCardChannelClickable(state),
                        )
                    }
                },
                actions = {
                    VideoCardSideActions(
                        state = state,
                        showWatched = false,
                        onWatched = actions.onWatched,
                        alignTo = 12.dp,
                    )
                },
            ) {
                StackedCardText(video = video, state = state, showChannel = showChannel, reason = reason, dense = false)
            }
        }

        if (!dense && !LocalDpadNavigation.current && (cardPreferences.actionsEnabled || cardPreferences.markWatchedEnabled)) {
            VideoCardFeedback(
                state = state,
                showRating = cardPreferences.actionsEnabled,
                showWatched = cardPreferences.markWatchedEnabled,
                actions = actions,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VideoCardDefaults.Inset)
                        .padding(bottom = VideoCardDefaults.Inset),
            )
        }
    }

    VideoCardSheets(state = state, showChannel = showChannel, whyShown = whyShown)
}

@Composable
private fun StackedCardText(
    video: Video,
    state: VideoCardState,
    showChannel: Boolean,
    reason: String?,
    dense: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (dense) DenseTextSpacing else TextSpacing),
    ) {
        Text(
            text = state.title,
            style = if (dense) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text =
                videoMetadataLine(
                    video = video,
                    isUpcoming = video.isUpcoming,
                    channelName = state.channelName,
                    includeChannel = showChannel,
                ),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (video.isUpcoming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = if (dense) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )

        MembersOnlyLabel(video)
        reason?.let { CardReasonLabel(it) }
    }
}

private val TextSpacing = 4.dp
private val DenseTextSpacing = 2.dp
private val DenseDetailsTopPadding = 6.dp
private val DenseDetailsBottomPadding = 8.dp

/** Whether [width] holds the avatar, the menu and a title of at least [minTitleWidth] side by side. */
internal fun showsAvatarBesideTitle(
    width: Int,
    avatarWidth: Int,
    actionsWidth: Int,
    gap: Int,
    minTitleWidth: Int,
): Boolean = avatarWidth > 0 && width - actionsWidth - gap - avatarWidth - gap >= minTitleWidth

/** Narrower than this, a title beside the avatar wraps to a few letters a line. */
private val MinTitleWidth = 80.dp

/**
 * The details under the thumbnail: avatar, text, then the menu. A card too narrow for a readable
 * title beside the avatar, such as three cards across a phone, leaves the avatar out; the channel
 * name is still in the line under the title.
 */
@Composable
private fun CardDetailsRow(
    spacing: Dp,
    avatar: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(avatar, text, actions),
        modifier = modifier,
    ) { (avatarMeasurables, textMeasurables, actionMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceables = actionMeasurables.map { it.measure(loose) }
        val actionsWidth = actionPlaceables.maxOfOrNull { it.width } ?: 0
        val avatarPlaceables = avatarMeasurables.map { it.measure(loose) }
        val avatarWidth = avatarPlaceables.maxOfOrNull { it.width } ?: 0
        val showAvatar = showsAvatarBesideTitle(width, avatarWidth, actionsWidth, gap, MinTitleWidth.roundToPx())
        val textX = if (showAvatar) avatarWidth + gap else 0
        val textWidth = (width - actionsWidth - gap - textX).coerceAtLeast(0)
        val textPlaceables = textMeasurables.map { it.measure(loose.copy(minWidth = textWidth, maxWidth = textWidth)) }
        val height =
            maxOf(
                textPlaceables.maxOfOrNull { it.height } ?: 0,
                if (showAvatar) avatarPlaceables.maxOfOrNull { it.height } ?: 0 else 0,
                actionPlaceables.maxOfOrNull { it.height } ?: 0,
            ).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            if (showAvatar) avatarPlaceables.forEach { it.placeRelative(0, 0) }
            textPlaceables.forEach { it.placeRelative(textX, 0) }
            actionPlaceables.forEach { it.placeRelative(width - actionsWidth, 0) }
        }
    }
}
