package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val TilePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val TileMinHeight = 56.dp
private val TileGap = 8.dp
private val GridPadding = 16.dp

/**
 * One choice of the download sheet: an M3 Expressive toggle button, so the checked one fills with
 * the primary colour and rounds its corners the way the toggle does on its own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadChoiceTile(
    title: String,
    detail: String?,
    badge: String?,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ToggleButtonColors = ToggleButtonDefaults.toggleButtonColors(),
) {
    val haptics = LocalHapticFeedback.current
    val themeShapes = MaterialTheme.shapes
    ToggleButton(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shapes = ToggleButtonShapes(shape = themeShapes.medium, pressedShape = themeShapes.small, checkedShape = themeShapes.extraLarge),
        contentPadding = TilePadding,
        colors = colors,
        modifier = modifier.heightIn(min = TileMinHeight),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    val colors = MaterialTheme.colorScheme
                    MediaTextBadge(
                        text = badge,
                        containerColor = if (checked) colors.onPrimary else colors.surfaceContainerHighest,
                        contentColor = if (checked) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** [items] in rows of [columns] equal tiles; a short last row keeps the tile width. */
@Composable
internal fun <T> DownloadTileGrid(
    items: List<T>,
    columns: Int,
    tile: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = GridPadding),
        verticalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                row.forEach { item -> tile(item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
