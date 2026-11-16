@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.ui.components.musicplayer.mini.coverPolygon
import io.github.aedev.flow.ui.components.musicplayer.mini.coverShapeLabel
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState

private val ShapeSize = 52.dp
private val CellMinWidth = 88.dp
private const val NARROW_SHAPE_ALPHA = 0.6f

/**
 * Every cover shape in a grid. [multiple] lets several be picked, keeping at least one; otherwise
 * one is, and [keepTrackShape] adds the choice of the song's own shape (a null pick).
 */
@Composable
internal fun CoverShapeSheet(
    title: String,
    subtitle: String,
    selected: Set<CoverShape?>,
    multiple: Boolean,
    onPick: (CoverShape?) -> Unit,
    onDismiss: () -> Unit,
    keepTrackShape: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        FlowSheetHeader(title = title, subtitle = subtitle, onClose = onDismiss, showDragHandle = false)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CellMinWidth),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (keepTrackShape) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        FilterChip(
                            selected = null in selected,
                            onClick = { onPick(null) },
                            label = { Text(stringResource(R.string.player_appearance_cover_paused_keep)) },
                        )
                    }
                }
            }
            items(CoverShape.entries) { shape ->
                ShapeCell(shape, isSelected = shape in selected, multiple = multiple, onPick = { onPick(shape) })
            }
        }
    }
}

@Composable
private fun ShapeCell(
    shape: CoverShape,
    isSelected: Boolean,
    multiple: Boolean,
    onPick: () -> Unit,
) {
    val pick =
        if (multiple) {
            Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onPick() })
        } else {
            Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onPick)
        }
    Column(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.large)
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .then(pick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier =
                    Modifier
                        .size(ShapeSize)
                        .alpha(if (shape.roomy) 1f else NARROW_SHAPE_ALPHA)
                        .clip(shape.coverPolygon().toShape())
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraLarge),
                )
            }
        }
        Text(
            text = stringResource(coverShapeLabel(shape)),
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
