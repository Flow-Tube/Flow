package io.github.aedev.flow.ui.components.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.plainRichText
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState
import io.github.aedev.flow.ui.components.shared.rememberRichTextInlineContent
import io.github.aedev.flow.ui.openYouTubeUrl
import io.github.aedev.flow.utils.toAnnotatedString

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowDescriptionBottomSheet(
    video: Video,
    onDismiss: () -> Unit,
    onSeekMs: (Long) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null,
    onTagClick: ((String) -> Unit)? = null,
    descriptionPage: VideoDescriptionPage? = null,
    tags: List<String> = emptyList(),
    chapterCount: Int = 0,
    onChaptersClick: (() -> Unit)? = null,
    onTranscriptClick: (() -> Unit)? = null,
    note: String? = null,
    onEditNote: (() -> Unit)? = null,
    onChannelClick: ((String) -> Unit)? = null,
    artworkUrl: String? = null,
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    onSheetProgressChange: (Float) -> Unit = {},
    dismissOnOutsideTap: Boolean = false,
    enableVerticalDismiss: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalMediaNavigator.current
    val context = LocalContext.current
    val sheetState = rememberFlowBottomSheetState()
    val openLink: (String) -> Unit = { url ->
        if (navigator.openYouTubeUrl(url)) sheetState.dismiss() else runCatching { uriHandler.openUri(url) }
    }
    val descriptionScrollState = rememberScrollState()
    val tint = rememberMediaArtworkTint(artworkUrl ?: video.thumbnailUrl)
    val linkColor = tint.accent
    val textColor = MaterialTheme.colorScheme.onSurface

    // With original titles on, the description the player sent wins over the translated one (#1230).
    val showOriginal = LocalVideoCardPreferences.current.originalTitlesEnabled && video.description.isNotBlank()
    val richDescription =
        if (showOriginal) remember(video.description) { plainRichText(video.description) } else descriptionPage?.description
    val descriptionEmoji = rememberRichTextInlineContent(richDescription)
    val descriptionText =
        remember(richDescription, video.description, linkColor, textColor) {
            richDescription?.toAnnotatedString(linkColor = linkColor, textColor = textColor)
                ?: parseHtmlDescription(video.description, linkColor)
        }
    var descLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // The server marks its own hashtags; a description that only arrived as HTML is still scanned.
    val hashtags =
        remember(richDescription, descriptionText.text) {
            richDescription
                ?.spans
                ?.mapNotNull { span -> (span.target as? RichTextTarget.Hashtag)?.tag }
                ?.map { tag -> if (tag.startsWith("#")) tag else "#$tag" }
                ?.distinct()
                ?.take(5)
                ?: Regex("""#\w+""")
                    .findAll(descriptionText.text)
                    .map { it.value }
                    .distinct()
                    .take(5)
                    .toList()
        }

    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight ?: defaultSheetExpandedHeight(),
        collapsedHeight = collapsedHeight,
        dismissible = enableVerticalDismiss,
        dismissOnOutsideTap = dismissOnOutsideTap,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            FlowSheetHeader(
                inSidePane = !enableVerticalDismiss,
                title = stringResource(R.string.description),
                onClose = { sheetState.dismiss() },
                modifier = dragModifier,
                titleStyle =
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                actions = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("description", descriptionText.text)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.description_copied),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_description))
                    }
                },
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(descriptionScrollState)
                    .padding(horizontal = SheetHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            FlowSelectionContainer {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val stats = descriptionPage?.factoids?.takeIf { it.isNotEmpty() } ?: fallbackFactoids(video, descriptionPage)

            Row(horizontalArrangement = Arrangement.spacedBy(CardSpacing)) {
                stats.forEach { factoid ->
                    FactoidCard(
                        factoid = factoid,
                        tint = tint,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            DescriptionBody(
                text = descriptionText,
                inlineContent = descriptionEmoji,
                tint = tint,
                onLayout = { descLayoutResult = it },
                onTap = { offset ->
                    descriptionText.handleDescriptionTap(
                        offset = offset,
                        onSeekMs = onSeekMs,
                        onHashtagClick = onHashtagClick,
                        onChannelClick = onChannelClick ?: navigator::openChannel,
                        onOpenUrl = openLink,
                    )
                },
                layoutResult = descLayoutResult,
            )

            if (hashtags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                    verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                ) {
                    hashtags.forEach { tag ->
                        Text(
                            text = tag,
                            color = tint.accent,
                            style = MaterialTheme.typography.labelLarge,
                            modifier =
                                if (onHashtagClick == null) {
                                    Modifier
                                } else {
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable { onHashtagClick(tag.removePrefix("#")) }
                                },
                        )
                    }
                }
            }

            if (chapterCount > 0 && onChaptersClick != null) {
                DescriptionSectionRow(
                    title = stringResource(R.string.chapters),
                    subtitle = pluralStringResource(R.plurals.chapters_count_template, chapterCount, chapterCount),
                    tint = tint,
                    onClick = onChaptersClick,
                )
            }

            if (onTranscriptClick != null) {
                DescriptionSectionRow(
                    title = stringResource(R.string.transcript),
                    subtitle = stringResource(R.string.transcript_subtitle),
                    tint = tint,
                    onClick = onTranscriptClick,
                )
            }

            if (onEditNote != null) {
                if (note.isNullOrBlank()) {
                    DescriptionSectionRow(
                        title = stringResource(R.string.note_title),
                        subtitle = stringResource(R.string.note_add),
                        tint = tint,
                        onClick = onEditNote,
                    )
                } else {
                    FlowNoteCard(
                        text = note,
                        onEdit = onEditNote,
                        containerColor = tint.container,
                        contentColor = tint.onContainer,
                        linkColor = tint.accent,
                        durationMs = video.duration * 1000L,
                        onTimestampClick = onSeekMs.takeUnless { video.isLive },
                    )
                }
            }

            descriptionPage?.channel?.let { channel ->
                ChannelCard(
                    channel = channel,
                    tint = tint,
                    onChannelClick = onChannelClick,
                    onOpenLink = openLink,
                )
            }

            if (tags.isNotEmpty()) {
                val sortedTags =
                    remember(tags) {
                        tags.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                    }
                Column(verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing)) {
                    Text(
                        text = stringResource(R.string.tags),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                        verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing),
                    ) {
                        sortedTags.forEach { tag ->
                            Surface(
                                shape = CircleShape,
                                color = tint.container,
                                contentColor = tint.onContainer,
                                modifier =
                                    if (onTagClick == null) Modifier else Modifier.clickable { onTagClick(tag) },
                            ) {
                                Text(
                                    text = tag,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(SheetBottomSpacing))
        }
    }
}

private val SheetHorizontalPadding = 16.dp
private val SheetBottomSpacing = 32.dp
private val SectionSpacing = 16.dp
private val CardSpacing = 8.dp
