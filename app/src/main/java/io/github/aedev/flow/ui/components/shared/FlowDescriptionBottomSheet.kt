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
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.plainRichText
import io.github.aedev.flow.data.translation.TitledTranslation
import io.github.aedev.flow.data.translation.splitTitle
import io.github.aedev.flow.data.translation.titledText
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
import io.github.aedev.flow.utils.youtubeWatchUrl
import kotlinx.coroutines.launch

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
    val linkActions =
        rememberRichTextLinkActions(
            onSeekMs = onSeekMs,
            onOpenUrl = openLink,
            onChannel = onChannelClick ?: navigator::openChannel,
            onHashtag = onHashtagClick,
        )
    val descriptionBlocks =
        remember(richDescription, video.description, video.id, linkColor, textColor, linkActions) {
            richDescription
                ?.descriptionBlocks(ownVideoId = video.id)
                ?.map { block ->
                    block.mapText { it.toAnnotatedString(linkColor = linkColor, textColor = textColor, actions = linkActions) }
                }
                ?: listOf(DescriptionBlock.Text(parseHtmlDescription(video.description, linkColor, linkActions)))
        }
    val descriptionText =
        remember(richDescription, descriptionBlocks) {
            richDescription?.text ?: descriptionBlocks.joinToString("\n") { (it as? DescriptionBlock.Text)?.text?.text.orEmpty() }
        }
    val translateAction = rememberTranslateAction()
    val scope = rememberCoroutineScope()
    var translated by remember(video.id) { mutableStateOf<TitledTranslation?>(null) }
    var translating by remember(video.id) { mutableStateOf(false) }
    val shownDescription =
        remember(translated, descriptionBlocks) {
            translated?.body?.takeIf { it.isNotBlank() }?.let { listOf(DescriptionBlock.Text(AnnotatedString(it))) }
                ?: descriptionBlocks
        }

    // The server marks its own hashtags; a description that only arrived as HTML is still scanned.
    val hashtags =
        remember(richDescription, descriptionText) {
            richDescription
                ?.spans
                ?.mapNotNull { span -> (span.target as? RichTextTarget.Hashtag)?.tag }
                ?.map { tag -> if (tag.startsWith("#")) tag else "#$tag" }
                ?.distinct()
                ?.take(5)
                ?: Regex("""#\w+""")
                    .findAll(descriptionText)
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
                            if (translated != null) {
                                translated = null
                            } else if (!translating) {
                                scope.launch {
                                    translating = true
                                    try {
                                        translated = translateAction.translate(titledText(video.title, descriptionText))?.splitTitle()
                                    } finally {
                                        translating = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Translate,
                            contentDescription =
                                stringResource(
                                    if (translated !=
                                        null
                                    ) {
                                        R.string.translation_show_original
                                    } else {
                                        R.string.translate_description
                                    },
                                ),
                            tint = if (translated != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("description", descriptionText)
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
                    text = translated?.title?.takeIf { it.isNotBlank() } ?: video.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            translated?.let {
                Text(
                    text = stringResource(R.string.translation_by_host, it.host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                blocks = shownDescription,
                inlineContent = descriptionEmoji,
                tint = tint,
                onVideoClick = { card -> openLink(youtubeWatchUrl(card.videoId, card.startSeconds)) },
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
