package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.VideoDescriptionChannel
import io.github.aedev.flow.innertube.pages.VideoDescriptionFactoid

/** One of the three figures above the description: a big value over the label that names it. */
@Composable
internal fun FactoidCard(
    factoid: VideoDescriptionFactoid,
    tint: MediaArtworkTint,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = tint.container,
        contentColor = tint.onContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = FactoidVerticalPadding, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = factoid.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = factoid.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The description text in its own tinted card, collapsed until the reader asks for the rest.
 *
 * The button only appears once the text has actually overflowed, so a two-line description does
 * not get a control that expands nothing.
 */
@Composable
internal fun DescriptionBody(
    text: AnnotatedString,
    inlineContent: Map<String, InlineTextContent>,
    tint: MediaArtworkTint,
    layoutResult: TextLayoutResult?,
    onLayout: (TextLayoutResult) -> Unit,
    onTap: (Int) -> Unit,
) {
    val highlightColor = tint.onContainer.copy(alpha = HIGHLIGHT_ALPHA)
    var expanded by rememberSaveable(text.text) { mutableStateOf(false) }
    var overflowed by remember(text.text) { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = tint.container,
        contentColor = tint.onContainer,
        modifier = Modifier.fillMaxWidth().dpadReadThrough(),
    ) {
        Column(
            modifier = Modifier.padding(BodyPadding),
            verticalArrangement = Arrangement.spacedBy(BodySpacing),
        ) {
            FlowSelectionContainer {
                BasicText(
                    text = text,
                    inlineContent = inlineContent,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = tint.onContainer,
                            lineHeight = 24.sp,
                            fontSize = 15.sp,
                        ),
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        onLayout(result)
                        if (result.hasVisualOverflow) overflowed = true
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .richTextHighlights(
                                text = text,
                                layoutResult = { layoutResult },
                                color = highlightColor,
                            ).pointerInput(text) {
                                detectTapGestures(
                                    onTap = { tapOffset ->
                                        val result = layoutResult ?: return@detectTapGestures
                                        onTap(result.getOffsetForPosition(tapOffset))
                                    },
                                )
                            },
                )
            }

            if (overflowed) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tint.onContainer),
                    border = BorderStroke(width = 1.dp, color = tint.onContainer.copy(alpha = BODY_BUTTON_BORDER_ALPHA)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            if (expanded) {
                                stringResource(R.string.desc_see_less)
                            } else {
                                stringResource(R.string.desc_see_more)
                            },
                    )
                }
            }
        }
    }
}

/**
 * The creator behind the video, and the links they publish beside their channel.
 *
 * The links are the creator's own — a second channel, a social profile — and open outside the app,
 * which is why each carries the site's icon rather than a generic one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelCard(
    channel: VideoDescriptionChannel,
    tint: MediaArtworkTint,
    onChannelClick: ((String) -> Unit)?,
    onOpenLink: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = tint.container,
        contentColor = tint.onContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(BodyPadding),
            verticalArrangement = Arrangement.spacedBy(BodySpacing),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    if (onChannelClick == null || channel.channelId.isBlank()) {
                        Modifier
                    } else {
                        Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onChannelClick(channel.channelId) }
                    },
            ) {
                ChannelAvatarImage(
                    url = channel.avatarUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(ChannelAvatarSize)
                            .clip(CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (channel.subscribersText.isNotBlank()) {
                        Text(
                            text = channel.subscribersText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (channel.links.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                    verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                ) {
                    channel.links.forEach { link ->
                        Surface(
                            onClick = { onOpenLink(link.url) },
                            shape = CircleShape,
                            color = Color.Transparent,
                            contentColor = tint.onContainer,
                            border = BorderStroke(width = 1.dp, color = tint.onContainer.copy(alpha = BODY_BUTTON_BORDER_ALPHA)),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                if (link.iconUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = link.iconUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(LinkIconSize),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = link.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A titled row that opens another surface, in the same tinted card language as the body. */
@Composable
internal fun DescriptionSectionRow(
    title: String,
    subtitle: String,
    tint: MediaArtworkTint,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = tint.container,
        contentColor = tint.onContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

internal val DescriptionChipSpacing = 8.dp
private val FactoidVerticalPadding = 14.dp
private val BodyPadding = PaddingValues(16.dp)
private val BodySpacing = 12.dp
private val ChannelAvatarSize = 40.dp
private val LinkIconSize = 16.dp
private const val COLLAPSED_BODY_LINES = 6
private const val BODY_BUTTON_BORDER_ALPHA = 0.35f
private const val HIGHLIGHT_ALPHA = 0.12f
