package io.github.aedev.flow.ui.components.shorts

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.ShortLinkedVideo
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButtonSize
import io.github.aedev.flow.ui.components.shared.videoMetadataLine
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import io.github.aedev.flow.utils.YouTubeLink

private val AvatarSize = 36.dp
private val ChannelRowSpacing = 8.dp
private val TitleSpacing = 8.dp
private val MetadataSpacing = 2.dp
private val SoundPillSpacing = 6.dp
private val LinkedVideoSpacing = 4.dp
private val SubscribeToggleTouchSize = 48.dp
private val SubscribeToggleSize = 32.dp
private val SubscribeToggleIconSize = 22.dp
private val SubscribeToggleBorder = 1.dp
private val SoundBarWidth = 2.dp
private val SoundBarMinHeight = 4.dp
private val SoundBarMaxHeight = 12.dp

/** Channel, title, views and date, the video the reel links to, and the sound it plays, down a reel's left edge. */
@Composable
internal fun ShortsMetadataOverlay(
    short: ShortVideo,
    video: Video,
    isSubscribed: Boolean,
    isPlaying: Boolean,
    style: ShortsOverlayStyle,
    onChannelClick: () -> Unit,
    onSubscribeToggle: () -> Unit,
    onDescriptionClick: () -> Unit,
    modifier: Modifier = Modifier,
    isNotificationsEnabled: Boolean = false,
    onNotificationChange: ((Boolean) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onChannelClick),
        ) {
            ChannelAvatarImage(
                url = short.channelThumbnailUrl,
                contentDescription = short.channelName,
                modifier =
                    Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.width(ChannelRowSpacing))
            Text(
                text = short.channelName,
                style = MaterialTheme.typography.titleMedium.copy(shadow = ShortsOverlayDefaults.TextShadow),
                fontWeight = FontWeight.Bold,
                color = PlayerScrimContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(ChannelRowSpacing))
            when (style.subscribeControl) {
                ShortsSubscribeControl.IconToggle -> {
                    ShortsSubscribeIconToggle(
                        isSubscribed = isSubscribed,
                        channelName = short.channelName,
                        onToggle = onSubscribeToggle,
                    )
                }

                ShortsSubscribeControl.Button -> {
                    FlowSubscribeButton(
                        isSubscribed = isSubscribed,
                        onSubscribeClick = onSubscribeToggle,
                        onUnsubscribeClick = onSubscribeToggle,
                        isNotificationsEnabled = isNotificationsEnabled,
                        onNotificationChange = onNotificationChange,
                        size = FlowSubscribeButtonSize.Compact,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(TitleSpacing))

        Text(
            text = short.title,
            style = MaterialTheme.typography.bodyMedium.copy(shadow = ShortsOverlayDefaults.TextShadow),
            color = PlayerScrimContent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onDescriptionClick),
        )

        val metadata = videoMetadataLine(video, isUpcoming = false)
        if (metadata.isNotBlank()) {
            Spacer(modifier = Modifier.height(MetadataSpacing))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall.copy(shadow = ShortsOverlayDefaults.TextShadow),
                color = PlayerScrimContent,
            )
        }

        short.linkedVideo?.let { linkedVideo ->
            Spacer(modifier = Modifier.height(LinkedVideoSpacing))
            ShortsLinkedVideoChip(linkedVideo)
        }

        if (short.soundTitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(SoundPillSpacing))
            ShortsSoundPill(title = short.soundTitle, animate = isPlaying)
        }
    }
}

/** The compact subscribe control of the simple layout: a square that flips from a plus to a tick. */
@Composable
private fun ShortsSubscribeIconToggle(
    isSubscribed: Boolean,
    channelName: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val description = stringResource(if (isSubscribed) R.string.unsubscribe else R.string.action_subscribe)
    val contentColor = if (isSubscribed) PlayerScrimContent else MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = {
            onToggle()
            val toast =
                if (isSubscribed) {
                    context.getString(R.string.unsubscribed_from, channelName)
                } else {
                    context.getString(R.string.subscribed_to, channelName)
                }
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = Modifier.size(SubscribeToggleTouchSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isSubscribed) Color.Transparent else MaterialTheme.colorScheme.primary,
                contentColor = contentColor,
                border = if (isSubscribed) BorderStroke(SubscribeToggleBorder, PlayerScrimContent) else null,
                modifier = Modifier.size(SubscribeToggleSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = description,
                        modifier = Modifier.size(SubscribeToggleIconSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortsLinkedVideoChip(linkedVideo: ShortLinkedVideo) {
    val navigator = LocalMediaNavigator.current
    AssistChip(
        onClick = { navigator.openLink(YouTubeLink.Video(linkedVideo.id, isMusic = false)) },
        label = {
            Text(
                text = linkedVideo.title ?: stringResource(R.string.shorts_linked_video),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = PlayerScrimPanel,
                labelColor = PlayerScrimContent,
                leadingIconContentColor = PlayerScrimContent,
            ),
        border = null,
    )
}

@Composable
private fun ShortsSoundPill(
    title: String,
    animate: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayingWaveform(
            color = PlayerScrimContent,
            animate = animate,
            barWidth = SoundBarWidth,
            minBarHeight = SoundBarMinHeight,
            maxBarHeight = SoundBarMaxHeight,
        )
        Spacer(modifier = Modifier.width(SoundPillSpacing))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(shadow = ShortsOverlayDefaults.TextShadow),
            color = PlayerScrimContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
