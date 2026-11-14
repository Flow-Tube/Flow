package io.github.aedev.flow.ui.components.channel

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.contentfilter.aislist.AiVerdict
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.FlowNoteCard
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton
import io.github.aedev.flow.ui.components.shared.FullSizeImageDialog
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.copyPlainText
import kotlinx.coroutines.launch

@Composable
internal fun ChannelHeaderSection(
    header: ChannelHeader,
    isSubscribed: Boolean,
    isNotificationsEnabled: Boolean,
    onSubscribeClick: () -> Unit,
    onUnsubscribeClick: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onManageGroups: (() -> Unit)?,
    note: String?,
    onEditNote: (() -> Unit)?,
    aiVerdict: AiVerdict = AiVerdict.NONE,
    onShowAiChannel: () -> Unit = {},
) {
    val bannerUrl = remember(header.bannerUrl) { ThumbnailUrlResolver.resolveChannelBanner(header.bannerUrl, targetWidth = 2048) }
    var showFullSizeAvatar by remember(header.id) { mutableStateOf(false) }

    if (showFullSizeAvatar && header.avatarUrl.isNotEmpty()) {
        FullSizeImageDialog(
            imageUrl = header.avatarUrl,
            onDismiss = { showFullSizeAvatar = false },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!bannerUrl.isNullOrBlank()) {
            ChannelBanner(imageUrl = bannerUrl)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChannelAvatarImage(
                url = header.avatarUrl,
                contentDescription = stringResource(R.string.channel_avatar),
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = header.avatarUrl.isNotEmpty()) { showFullSizeAvatar = true },
            )

            Spacer(modifier = Modifier.weight(1f))

            FlowSubscribeButton(
                isSubscribed = isSubscribed,
                isNotificationsEnabled = isNotificationsEnabled,
                onSubscribeClick = onSubscribeClick,
                onUnsubscribeClick = onUnsubscribeClick,
                onNotificationChange = onNotificationChange,
                onManageGroups = onManageGroups,
                onAddNote = onEditNote,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ChannelName(header.title)

            val metadata = remember(header) { listOfNotNull(header.handle, header.subscriberCountText, header.videoCountText) }
            if (metadata.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    metadata.forEach { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.extendedColors.textSecondary,
                        )
                    }
                }
            }

            if (aiVerdict != AiVerdict.NONE) {
                AiChannelBanner(
                    verdict = aiVerdict,
                    onShow = onShowAiChannel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!note.isNullOrBlank() && onEditNote != null) {
                FlowNoteCard(
                    text = note,
                    onEdit = onEditNote,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Why this channel's videos are hidden or labelled elsewhere, with the way to show them again. */
@Composable
private fun AiChannelBanner(
    verdict: AiVerdict,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text =
                    stringResource(
                        if (verdict == AiVerdict.BLOCK) R.string.ai_list_channel_blocked else R.string.ai_list_channel_warned,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShow) {
                Text(stringResource(R.string.ai_list_channel_show))
            }
        }
    }
}

// Subscribe button

/**
 * The channel's name, copied on long-press. Not a text selection: the header slides away as the
 * page scrolls, which would leave selection handles floating.
 */
@Composable
private fun ChannelName(title: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.channel_name_clip_label)
    val copyLabel = stringResource(R.string.copy_channel_name)

    fun copy() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            clipboard.copyPlainText(clipLabel, title)
            Toast.makeText(context, context.getString(R.string.channel_name_copied), Toast.LENGTH_SHORT).show()
        }
    }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .pointerInput(title) { detectTapGestures(onLongPress = { copy() }) }
                .semantics {
                    onLongClick(label = copyLabel) {
                        copy()
                        true
                    }
                },
    )
}
