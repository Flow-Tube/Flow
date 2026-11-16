package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.LiveChatMessageType
import io.github.aedev.flow.data.model.LiveChatSegment
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.ui.theme.LiveChatMemberGreen
import io.github.aedev.flow.ui.theme.LiveChatMemberSurface
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun LiveChatList(
    messages: List<LiveChatMessage>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
) {
    val uniqueMessages =
        remember(messages) {
            messages.distinctByNonBlankKey(LiveChatMessage::id)
        }
    when {
        isLoading && uniqueMessages.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.live_chat_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        uniqueMessages.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.live_chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            var followLive by remember { mutableStateOf(true) }
            val isAtLiveEdge by remember {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    layoutInfo.totalItemsCount == 0 ||
                        (lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 1)
                }
            }

            LaunchedEffect(listState) {
                snapshotFlow { isAtLiveEdge to listState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collect { (atLiveEdge, isScrolling) ->
                        if (atLiveEdge) {
                            followLive = true
                        } else if (isScrolling) {
                            followLive = false
                        }
                    }
            }

            LaunchedEffect(uniqueMessages.lastOrNull()?.id, followLive) {
                if (followLive && uniqueMessages.isNotEmpty()) {
                    listState.scrollToItem(uniqueMessages.lastIndex)
                }
            }

            Box(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = contentPadding,
                ) {
                    items(uniqueMessages, key = { it.id }) { message ->
                        when (message.type) {
                            LiveChatMessageType.SUPER_CHAT -> SuperChatRow(message)
                            LiveChatMessageType.MEMBERSHIP -> MembershipRow(message)
                            else -> ChatTextRow(message)
                        }
                    }
                }

                if (!isAtLiveEdge) {
                    SmallFloatingActionButton(
                        onClick = {
                            followLive = true
                            coroutineScope.launch {
                                listState.animateScrollToItem(uniqueMessages.lastIndex)
                            }
                        },
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.live_chat_jump_to_latest),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTextRow(message: LiveChatMessage) {
    val tint =
        when {
            message.isOwner -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            message.isModerator -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else -> Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChatAvatar(message.authorPhotoUrl, message.memberBadgeUrl)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            AuthorLine(message)
            ChatMessageText(
                segments = message.segments,
                fallback = message.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MembershipRow(message: LiveChatMessage) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LiveChatMemberSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(message.authorPhotoUrl, message.memberBadgeUrl)
            Spacer(Modifier.width(8.dp))
            Text(
                text = message.author.ifBlank { "—" },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = LiveChatMemberGreen,
            )
        }
        if (message.message.isNotBlank() || message.segments.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            ChatMessageText(
                segments = message.segments,
                fallback = message.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SuperChatRow(message: LiveChatMessage) {
    val bodyColor = message.superChatArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.tertiaryContainer
    val headerColor = message.superChatHeaderArgb?.let { Color(it) } ?: bodyColor
    val onBody = if (bodyColor.luminance() > 0.5f) Color.Black else Color.White
    val onHeader = if (headerColor.luminance() > 0.5f) Color.Black else Color.White
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bodyColor),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(message.authorPhotoUrl, message.memberBadgeUrl)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = message.author,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = onHeader,
                )
                message.superChatAmount?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = onHeader,
                    )
                }
            }
        }
        if (message.message.isNotBlank() || message.segments.isNotEmpty()) {
            ChatMessageText(
                segments = message.segments,
                fallback = message.message,
                style = MaterialTheme.typography.bodyMedium,
                color = onBody,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ChatMessageText(
    segments: List<LiveChatSegment>,
    fallback: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) {
        Text(text = fallback, style = style, color = color, modifier = modifier)
        return
    }
    if (segments.none { it.emojiImageUrl != null }) {
        Text(
            text = segments.joinToString("") { it.text },
            style = style,
            color = color,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val inlineContent = HashMap<String, InlineTextContent>()
    val annotated =
        buildAnnotatedString {
            segments.forEachIndexed { index, seg ->
                val imageUrl = seg.emojiImageUrl
                if (imageUrl != null) {
                    val key = "emoji_$index"
                    appendInlineContent(key, seg.text.ifBlank { ":emoji:" })
                    inlineContent[key] =
                        InlineTextContent(
                            placeholder =
                                Placeholder(
                                    width = 1.4.em,
                                    height = 1.4.em,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                                ),
                        ) {
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(context)
                                        .data(imageUrl)
                                        .crossfade(true)
                                        .build(),
                                contentDescription = seg.text,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                } else {
                    append(seg.text)
                }
            }
        }
    Text(
        text = annotated,
        style = style,
        color = color,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}

@Composable
private fun AuthorLine(message: LiveChatMessage) {
    val authorColor =
        when {
            message.isOwner -> MaterialTheme.colorScheme.error
            message.isModerator -> MaterialTheme.colorScheme.primary
            message.isMember -> LiveChatMemberGreen
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.isModerator) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = message.author.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = authorColor,
        )
        if (message.isVerified) {
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        message.timestamp?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
fun LiveChatPreview(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isReplay: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isReplay) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(if (isReplay) R.string.chat_replay else R.string.live_chat),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatAvatar(
    url: String?,
    memberBadgeUrl: String? = null,
) {
    Box {
        if (url.isNullOrBlank()) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(url)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape),
            )
        }
        if (!memberBadgeUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(memberBadgeUrl)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                modifier =
                    Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape),
            )
        }
    }
}
