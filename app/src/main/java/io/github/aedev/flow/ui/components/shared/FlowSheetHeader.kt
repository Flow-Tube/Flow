package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

/** The values every player bottom sheet header shares; a caller overrides only where it differs. */
object FlowSheetHeaderDefaults {
    val ContentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 6.dp)
    val CloseButtonSize: Dp = 40.dp
    val DividerAlpha: Float = 0.2f
    val SidePaneTopPadding: Dp = 12.dp

    val titleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)

    val subtitleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium
}

/**
 * The header a [FlowBottomSheet] draws when it is a titled player sheet: the M3 drag handle, a
 * title row, and the divider that separates it from the sheet body.
 *
 * Pass this the drag modifier the sheet hands its header slot, so the whole header drags the sheet
 * exactly as the handle does.
 *
 * @param onClose closes the sheet from a close button, or null for a sheet that closes only by drag
 *   and back, with no button.
 * @param closeButtonSize fixed size for the close button, or null to leave it at the `IconButton`
 *   default.
 * @param dividerAlpha alpha for the `outlineVariant` divider, or null for a header with no divider.
 * @param showDragHandle false inside a `ModalBottomSheet`, which already draws its own handle.
 * @param inSidePane true when the sheet fills a side pane or drawer and cannot be dragged: no
 *   handle, and space above the title in its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowSheetHeader(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    titleStyle: TextStyle = FlowSheetHeaderDefaults.titleStyle,
    contentPadding: PaddingValues = FlowSheetHeaderDefaults.ContentPadding,
    closeButtonSize: Dp? = FlowSheetHeaderDefaults.CloseButtonSize,
    dividerAlpha: Float? = FlowSheetHeaderDefaults.DividerAlpha,
    actions: @Composable RowScope.() -> Unit = {},
    showDragHandle: Boolean = true,
    titleMaxLines: Int = Int.MAX_VALUE,
    inSidePane: Boolean = false,
) {
    Column(modifier = modifier) {
        if (inSidePane) {
            Spacer(Modifier.height(FlowSheetHeaderDefaults.SidePaneTopPadding))
        } else if (showDragHandle) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle()
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            // A leading icon sits in a header that spaces every child, including the close button.
            horizontalArrangement = if (leadingIcon != null) Arrangement.spacedBy(12.dp) else Arrangement.Start,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = FlowSheetHeaderDefaults.subtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = if (closeButtonSize != null) Modifier.size(closeButtonSize) else Modifier,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
        }

        if (dividerAlpha != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha))
        }
    }
}
