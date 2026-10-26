package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheetController

internal val DownloadSheetPadding = 16.dp

/**
 * The space a screen keeps for its media sheets, as the video player keeps the space under the
 * video: a sheet opened here sits in it at [expandedHeight] like the comments and description do,
 * instead of as a modal sheet over the screen, and reports [onProgressChange] so the host can make room.
 * [bottomPadding] keeps its last row clear of anything the host draws over its bottom edge.
 */
@Stable
class MediaSheetSlot(
    val expandedHeight: Dp,
    val collapsedHeight: Dp = 0.dp,
    val asSidePanel: Boolean = false,
    val bottomPadding: Dp = 0.dp,
    val onProgressChange: (Float) -> Unit = {},
)

/**
 * Both download sheets' container: the menu's modal sheet, or with a [slot] the player's own sheet.
 * [footer] stays pinned under the scrolling [content] in the slot, and ends the content in the menu.
 */
@Composable
internal fun DownloadSheetFrame(
    title: String,
    slot: MediaSheetSlot?,
    onDismiss: () -> Unit,
    footer: @Composable ColumnScope.(QuickActionsSheetController) -> Unit,
    content: @Composable ColumnScope.(QuickActionsSheetController) -> Unit,
) {
    if (slot == null) {
        QuickActionsSheet(onDismiss = onDismiss, expandFully = true) { sheet ->
            FlowSheetHeader(title = title, onClose = sheet::close, showDragHandle = false, dividerAlpha = null)
            content(sheet)
            footer(sheet)
        }
        return
    }
    val sheetState = rememberFlowBottomSheetState()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // The sheet reports its own dismissal once its exit animation ends, so a hand-off replaces it.
    var handOff by remember { mutableStateOf<(() -> Unit)?>(null) }
    val controller =
        remember(sheetState) {
            QuickActionsSheetController(
                hideThen = { after ->
                    handOff = after
                    sheetState.dismiss()
                },
                onDismiss = { latestOnDismiss() },
            )
        }
    FlowBottomSheet(
        onDismiss = { handOff?.invoke() ?: latestOnDismiss() },
        modifier = if (slot.asSidePanel) Modifier.fillMaxSize() else Modifier,
        state = sheetState,
        expandedHeight = slot.expandedHeight,
        collapsedHeight = slot.collapsedHeight,
        dismissible = !slot.asSidePanel,
        dismissOnOutsideTap = false,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = slot.onProgressChange,
        header = { dragModifier ->
            FlowSheetHeader(
                inSidePane = slot.asSidePanel,
                title = title,
                onClose = controller::close,
                modifier = dragModifier,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
        ) {
            content(controller)
        }
        footer(controller)
        Spacer(Modifier.height(DownloadSheetPadding + slot.bottomPadding))
    }
}

/** The one full-width button a download sheet ends on. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadSheetButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = DownloadSheetPadding, end = DownloadSheetPadding, top = DownloadSheetPadding)
                .height(ButtonDefaults.MediumContainerHeight),
    ) {
        Icon(imageVector = Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The colours of a download sheet's choices. The player's sheets are the surface colour, which the
 * theme also gives the toggle buttons' container, so in a [slot] they take the lowest container instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun downloadToggleColors(slot: MediaSheetSlot?): ToggleButtonColors =
    if (slot == null) {
        ToggleButtonDefaults.toggleButtonColors()
    } else {
        ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    }
