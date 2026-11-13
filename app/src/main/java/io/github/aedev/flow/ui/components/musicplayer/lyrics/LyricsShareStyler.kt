package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterFrames
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FittedShareCard
import io.github.aedev.flow.ui.components.shared.FlowActionButtonPair
import io.github.aedev.flow.ui.components.shared.FlowColorSwatch
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowPairAction
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import io.github.aedev.flow.ui.utils.captureInto
import kotlinx.coroutines.launch

private val EdgePadding = 16.dp
private val ControlSpacing = 12.dp
private const val PNG_MIME = "image/png"

/** The card preview with its look controls, and the buttons that share or save it as an image. */
@Composable
internal fun ColumnScope.LyricsShareStyler(
    title: String,
    artist: String,
    artworkUrl: String,
    lines: List<String>,
    style: LyricsCardStyle,
    onStyleChange: (LyricsCardStyle) -> Unit,
    align: TextAlign,
    onAlignChange: (TextAlign) -> Unit,
    frame: LyricsCardFrame,
    onFrameChange: (LyricsCardFrame) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = rememberMediaPalette(artworkUrl, animated = false, cacheOnly = true)
    val cover = rememberLyricsCover(artworkUrl, enabled = true)
    val colorsByStyle = LyricsCardStyle.entries.associateWith { lyricsCardColors(it, palette, cover) }
    val styles = remember(colorsByStyle, cover) { distinctCardStyles(cover != null) { colorsByStyle.getValue(it).ground } }
    val colors = colorsByStyle.getValue(style)
    val scheme = MaterialTheme.colorScheme
    val backdrop = lyricsBackdropColor(colors.ground, listOf(palette.accent, scheme.inverseSurface, scheme.surfaceContainerHighest))
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

    Box(
        modifier =
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(EdgePadding),
        contentAlignment = Alignment.Center,
    ) {
        FittedShareCard(Modifier.widthIn(max = LyricsCardWidth)) {
            CompositionLocalProvider(LocalDensity provides LyricsCardExportDensity) {
                LyricsFramedCard(
                    frame = frame,
                    backdrop = backdrop,
                    modifier = Modifier.width(LyricsCardWidth).captureInto(layer),
                ) { shape ->
                    LyricsShareCard(
                        title = title,
                        artist = artist,
                        artworkUrl = artworkUrl,
                        lines = lines,
                        colors = colors,
                        textAlign = align,
                        shape = shape,
                        modifier = Modifier.fillMaxWidth(),
                        cover = cover.takeIf { style == LyricsCardStyle.COLORFUL },
                    )
                }
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
                FlowToggleOption(LyricsCardFrame.SQUARE, stringResource(R.string.lyrics_share_frame_square), Icons.Outlined.CropSquare),
                FlowToggleOption(
                    LyricsCardFrame.BACKDROP,
                    stringResource(R.string.lyrics_share_frame_backdrop),
                    Icons.Outlined.FilterFrames,
                ),
                FlowToggleOption(LyricsCardFrame.TRANSPARENT, stringResource(R.string.lyrics_share_frame_clear), Icons.Outlined.Opacity),
            ),
        selected = frame,
        onSelected = onFrameChange,
        modifier = Modifier.padding(start = EdgePadding, end = EdgePadding, top = ControlSpacing),
    )
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
