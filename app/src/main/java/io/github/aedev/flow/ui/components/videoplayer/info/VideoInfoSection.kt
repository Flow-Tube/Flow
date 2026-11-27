package io.github.aedev.flow.ui.components.videoplayer.info

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.innertube.pages.ChannelBadge
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.ChannelAvatarStack
import io.github.aedev.flow.ui.components.shared.ChannelOrganizeTarget
import io.github.aedev.flow.ui.components.shared.CollaboratorsBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButton
import io.github.aedev.flow.ui.components.shared.FlowSubscribeButtonSize
import io.github.aedev.flow.ui.components.shared.card.rememberCollaboratorChannelDisplayName
import io.github.aedev.flow.ui.components.shared.rememberChannelOrganizeActions
import io.github.aedev.flow.ui.components.shared.rememberDateDisplaySettings
import io.github.aedev.flow.ui.components.shared.rememberDateLabels
import io.github.aedev.flow.ui.components.shared.rememberRichTextLinkActions
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.DateContext
import io.github.aedev.flow.utils.avatarImageIdentityKey
import io.github.aedev.flow.utils.formatSubscriberCount
import io.github.aedev.flow.utils.formatViewCount
import io.github.aedev.flow.utils.toAnnotatedString

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VideoInfoSection(
    video: Video,
    title: String,
    titleMaxLines: Int,
    superTitle: RichText? = null,
    viewCount: Long,
    uploadDate: String?,
    description: String?,
    isUpcoming: Boolean = false,
    channelName: String,
    channelBadge: ChannelBadge? = null,
    channelAvatarUrl: String,
    channelAvatarUrls: List<String> = emptyList(),
    collaborators: List<VideoCollaborator> = emptyList(),
    subscribedCollaboratorIds: Set<String> = emptySet(),
    subscriberCount: Long?,
    isSubscribed: Boolean,
    isNotificationsEnabled: Boolean = false,
    likeState: String,
    likeCount: Long? = null,
    dislikeCount: Long?,
    onSubscribeClick: () -> Unit,
    onUnsubscribeClick: () -> Unit = {},
    onNotificationChange: (Boolean) -> Unit = {},
    onChannelClick: () -> Unit,
    onLikeClick: () -> Unit,
    onDislikeClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveClick: () -> Unit,
    onBackgroundPlayClick: () -> Unit,
    onCopyLinkClick: () -> Unit = {},
    onCopyLinkAtTimeClick: () -> Unit = {},
    onDescriptionClick: () -> Unit,
    isSaved: Boolean = false,
    isDownloaded: Boolean = false,
    onNoteClick: (() -> Unit)? = null,
    hasNote: Boolean = false,
    isDeviceFile: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showCollaborators by remember { mutableStateOf(false) }
    val displayChannelName = rememberCollaboratorChannelDisplayName(channelName, collaborators)
    val avatarUrls =
        remember(channelAvatarUrl, channelAvatarUrls, collaborators, video.channelThumbnailUrl) {
            val sources =
                if (collaborators.size > 1) {
                    collaborators.map { it.thumbnailUrl }
                } else {
                    listOf(channelAvatarUrl, video.channelThumbnailUrl) + channelAvatarUrls
                }
            sources
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.avatarImageIdentityKey() }
                .take(if (collaborators.size > 1) 3 else 1)
        }
    val openChannelOrCollaborators = {
        if (collaborators.size > 1) {
            showCollaborators = true
        } else {
            onChannelClick()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = InfoPadding),
    ) {
        // ============ TITLE SECTION ============
        val context = LocalContext.current
        val dateSettings = rememberDateDisplaySettings()
        val dateLabels = rememberDateLabels()
        // Title, counts and "…more" are one target: tapping any of them opens the description,
        // which is what the "…more" affordance was already promising.
        Column(
            modifier =
                Modifier
                    .padding(horizontal = InfoPadding)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onDescriptionClick),
        ) {
            superTitle?.let { VideoSuperTitle(it) }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (titleMaxLines <= 0) Int.MAX_VALUE else titleMaxLines,
                overflow = if (titleMaxLines <= 0) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier =
                    Modifier.combinedClickable(
                        onClick = onDescriptionClick,
                        onLongClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(context.getString(R.string.title_label), title),
                            )
                            Toast.makeText(context, context.getString(R.string.title_copied), Toast.LENGTH_SHORT).show()
                        },
                    ),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val views =
                    when {
                        isUpcoming && viewCount > 0L -> stringResource(R.string.upcoming_waiting_count, formatViewCount(viewCount))
                        isUpcoming -> stringResource(R.string.upcoming_label)
                        else -> stringResource(R.string.views_count_short_template, formatViewCount(viewCount))
                    }
                Text(
                    text =
                        if (!isUpcoming && !uploadDate.isNullOrBlank()) {
                            stringResource(
                                R.string.video_metadata_short_template,
                                views,
                                dateSettings.format(uploadDate, DateContext.WATCH, dateLabels, video.timestamp, video.timestampIsExact),
                            )
                        } else {
                            views
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUpcoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = stringResource(R.string.desc_more),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ============ CHANNEL SECTION ============
        Row(
            modifier =
                Modifier
                    .padding(horizontal = InfoPadding)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = !isDeviceFile) { openChannelOrCollaborators() },
            ) {
                ChannelAvatarStack(
                    urls = avatarUrls,
                    contentDescription = displayChannelName,
                    avatarSize = 44.dp,
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayChannelName,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // A collaboration names several channels, and the badge belongs to one of them.
                        if (channelBadge != null && collaborators.size <= 1) ChannelBadgeIcon(channelBadge)
                    }

                    val subText = subscriberCount?.let { formatSubscriberCount(it) } ?: ""
                    if (subText.isNotEmpty()) {
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.extendedColors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!isDeviceFile && collaborators.size > 1) {
                // One button cannot name which channel it follows, so it opens the list that can (#1160).
                FlowSubscribeButton(
                    size = FlowSubscribeButtonSize.Compact,
                    isSubscribed = collaborators.any { it.channelId in subscribedCollaboratorIds },
                    onSubscribeClick = { showCollaborators = true },
                    onSubscribedClick = { showCollaborators = true },
                )
            } else if (!isDeviceFile) {
                val organize =
                    rememberChannelOrganizeActions(
                        target =
                            remember(video.channelId, channelName, channelAvatarUrl) {
                                ChannelOrganizeTarget(
                                    channelId = video.channelId,
                                    title = channelName,
                                    avatarUrl = channelAvatarUrl.ifBlank { video.channelThumbnailUrl },
                                )
                            },
                        isSubscribed = isSubscribed,
                    )
                FlowSubscribeButton(
                    size = FlowSubscribeButtonSize.Compact,
                    isSubscribed = isSubscribed,
                    isNotificationsEnabled = isNotificationsEnabled,
                    onSubscribeClick = onSubscribeClick,
                    onUnsubscribeClick = onUnsubscribeClick,
                    onNotificationChange = onNotificationChange,
                    showSubscribedLabel = false,
                    onAddNote = organize.onAddNote,
                    onManageGroups = organize.onManageGroups,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ============ ACTION ROW ============
        VideoActionRow(
            likeState = likeState,
            likeCount = likeCount,
            dislikeCount = dislikeCount,
            onLikeClick = onLikeClick,
            onDislikeClick = onDislikeClick,
            onShareClick = onShareClick,
            onDownloadClick = onDownloadClick,
            onSaveClick = onSaveClick,
            onBackgroundPlayClick = onBackgroundPlayClick,
            onCopyLinkClick = onCopyLinkClick,
            onCopyLinkAtTimeClick = onCopyLinkAtTimeClick,
            isSaved = isSaved,
            isDownloaded = isDownloaded,
            onNoteClick = onNoteClick,
            hasNote = hasNote,
            isDeviceFile = isDeviceFile,
            contentPadding = PaddingValues(horizontal = InfoPadding),
        )
    }

    if (showCollaborators) {
        CollaboratorsBottomSheet(
            collaborators = collaborators,
            onDismiss = { showCollaborators = false },
        )
    }
}

/** The hashtags or the place above the title; a hashtag searches for itself, as Flow has no hashtag page. */
@Composable
private fun VideoSuperTitle(line: RichText) {
    val navigator = LocalMediaNavigator.current
    val color = MaterialTheme.colorScheme.primary
    val actions = rememberRichTextLinkActions(onHashtag = { tag -> navigator.openSearch("#$tag") })
    val text = remember(line, color, actions) { line.toAnnotatedString(linkColor = color, textColor = color, actions = actions) }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ChannelBadgeIcon(badge: ChannelBadge) {
    Icon(
        imageVector = if (badge == ChannelBadge.ARTIST) Icons.Rounded.MusicNote else Icons.Rounded.CheckCircle,
        contentDescription = stringResource(if (badge == ChannelBadge.ARTIST) R.string.channel_badge_artist else R.string.verified),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(start = 4.dp)
                .size(BadgeSize),
    )
}

private val InfoPadding = 12.dp
private val BadgeSize = 14.dp
