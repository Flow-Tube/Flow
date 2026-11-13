package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.ui.components.shared.FittedShareCard
import io.github.aedev.flow.ui.components.shared.FlowActionButton
import io.github.aedev.flow.ui.components.shared.FlowActionButtonPair
import io.github.aedev.flow.ui.components.shared.FlowColorSwatch
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.FlowPairAction
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import io.github.aedev.flow.ui.components.shared.toggleHapticType
import io.github.aedev.flow.ui.utils.captureInto
import kotlinx.coroutines.launch

private val EdgePadding = 16.dp
private val RowSpacing = 4.dp
private val ControlSpacing = 12.dp
private val LinePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private const val DISABLED_LINE_ALPHA = 0.38f
private const val PNG_MIME = "image/png"

/** Picks up to [MAX_SHARE_LINES] lyric lines, then styles the card they make and saves or shares it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsShareSheet(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<LyricsEntry>,
    positionProvider: () -> Long,
    initialAlign: TextAlign,
    onDismiss: () -> Unit,
) {
    val texts = remember(lines) { shareableLyricLines(lines) }
    var selected by remember(texts) { mutableStateOf(emptyList<Int>()) }
    var customising by remember { mutableStateOf(false) }
    // Held here so going back to change the lines keeps the look already picked.
    var style by remember { mutableStateOf(LyricsCardStyle.ARTWORK) }
    var align by remember { mutableStateOf(initialAlign) }
    val styling = customising && selected.isNotEmpty()
    val sheetState = rememberFlowSheetState()
    val scope = rememberCoroutineScope()
    val properties = remember(styling) { ModalBottomSheetProperties(shouldDismissOnBackPress = !styling) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = properties,
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        BackHandler(enabled = styling) { customising = false }
        FlowSheetHeader(
            title = stringResource(R.string.lyrics_share),
            subtitle = stringResource(R.string.lyrics_share_selected, selected.size, MAX_SHARE_LINES),
            onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
            onBack = if (styling) ({ customising = false }) else null,
            showDragHandle = false,
        )
        if (styling) {
            LyricsShareStyler(
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
                lines = selected.map { texts[it] },
                style = style,
                onStyleChange = { style = it },
                align = align,
                onAlignChange = { align = it },
            )
        } else {
            LyricsSharePicker(
                texts = texts,
                selected = selected,
                // Read once: the picker opens at the line being sung and must not follow playback.
                initialIndex = remember(lines) { Snapshot.withoutReadObservation { shareLineIndexAt(lines, positionProvider()) } },
                onToggle = { selected = toggleShareLine(selected, it) },
                onNext = { customising = true },
            )
        }
    }
}

@Composable
private fun ColumnScope.LyricsSharePicker(
    texts: List<String>,
    selected: List<Int>,
    initialIndex: Int,
    onToggle: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex),
        modifier = Modifier.weight(1f, fill = false),
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = ControlSpacing),
        verticalArrangement = Arrangement.spacedBy(RowSpacing),
    ) {
        itemsIndexed(texts) { index, text ->
            val isSelected = index in selected
            val enabled = isSelected || selected.size < MAX_SHARE_LINES
            Surface(
                selected = isSelected,
                onClick = {
                    haptics.performHapticFeedback(toggleHapticType(!isSelected))
                    onToggle(index)
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor =
                    when {
                        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                        enabled -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_LINE_ALPHA)
                    },
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(LinePadding),
                    style = lyricsShareLineStyle(MaterialTheme.typography.titleLarge),
                )
            }
        }
    }
    FlowActionButton(
        text = stringResource(R.string.next),
        onClick = onNext,
        enabled = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = EdgePadding, vertical = ControlSpacing),
    )
}

@Composable
private fun ColumnScope.LyricsShareStyler(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<String>,
    style: LyricsCardStyle,
    onStyleChange: (LyricsCardStyle) -> Unit,
    align: TextAlign,
    onAlignChange: (TextAlign) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = rememberMediaPalette(artworkUrl, animated = false, cacheOnly = true)
    val cover = rememberLyricsCover(artworkUrl, enabled = true)
    val colorsByStyle = LyricsCardStyle.entries.associateWith { lyricsCardColors(it, palette, cover) }
    val styles = remember(colorsByStyle, cover) { distinctCardStyles(cover != null) { colorsByStyle.getValue(it).ground } }
    val layer = rememberGraphicsLayer()
    var pendingSave by remember { mutableStateOf<ImageBitmap?>(null) }

    fun report(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PNG_MIME)) { uri ->
            val image = pendingSave
            pendingSave = null
            if (uri != null) {
                if (image == null) {
                    // The card held for the picker is gone when Flow was recreated behind it.
                    report(R.string.lyrics_share_image_failed)
                } else {
                    scope.launch {
                        val saved = saveLyricsCard(context, image, uri)
                        report(if (saved) R.string.lyrics_share_image_saved else R.string.lyrics_share_image_failed)
                    }
                }
            }
        }
    val chooserTitle = stringResource(R.string.lyrics_share)

    val fontScale = LocalDensity.current.fontScale
    val exportDensity = remember(fontScale) { lyricsCardExportDensity(fontScale) }
    Box(
        modifier =
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(EdgePadding),
        contentAlignment = Alignment.Center,
    ) {
        FittedShareCard(Modifier.widthIn(max = LyricsCardWidth)) {
            CompositionLocalProvider(LocalDensity provides exportDensity) {
                LyricsShareCard(
                    title = title,
                    artist = artist,
                    artworkUrl = artworkUrl,
                    lines = lines,
                    colors = colorsByStyle.getValue(style),
                    textAlign = align,
                    modifier = Modifier.width(LyricsCardWidth).captureInto(layer),
                    cover = cover.takeIf { style == LyricsCardStyle.COLORFUL },
                )
            }
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(ControlSpacing),
    ) {
        items(styles) { option ->
            FlowColorSwatch(
                color = colorsByStyle.getValue(option).ground,
                selected = option == style,
                onClick = { onStyleChange(option) },
                fill =
                    cover?.takeIf { option == LyricsCardStyle.COLORFUL }?.let { field ->
                        { LyricsCoverLayer(cover = field, moving = false, running = false) }
                    },
            )
        }
    }
    FlowConnectedToggleGroup(
        options =
            listOf(
                FlowToggleOption(TextAlign.Left, stringResource(R.string.lyrics_share_align_left), Icons.Outlined.FormatAlignLeft),
                FlowToggleOption(TextAlign.Center, stringResource(R.string.lyrics_share_align_center), Icons.Outlined.FormatAlignCenter),
                FlowToggleOption(TextAlign.Right, stringResource(R.string.lyrics_share_align_right), Icons.Outlined.FormatAlignRight),
            ),
        selected = align,
        onSelected = onAlignChange,
        modifier = Modifier.padding(horizontal = EdgePadding, vertical = ControlSpacing),
    )
    FlowActionButtonPair(
        primary =
            FlowPairAction(Icons.Outlined.Share, stringResource(R.string.share)) {
                scope.launch {
                    val image = runCatching { layer.toImageBitmap() }.getOrNull()
                    if (image == null || !shareLyricsCard(context, image, chooserTitle)) report(R.string.lyrics_share_image_failed)
                }
            },
        secondary =
            FlowPairAction(Icons.Outlined.SaveAlt, stringResource(R.string.lyrics_share_save_image)) {
                scope.launch {
                    val image = runCatching { layer.toImageBitmap() }.getOrNull()
                    if (image == null) {
                        report(R.string.lyrics_share_image_failed)
                    } else {
                        pendingSave = image
                        saveLauncher.launch(lyricsFileName(title, artist, "png"))
                    }
                }
            },
        modifier = Modifier.padding(start = EdgePadding, end = EdgePadding, bottom = ControlSpacing),
    )
}
