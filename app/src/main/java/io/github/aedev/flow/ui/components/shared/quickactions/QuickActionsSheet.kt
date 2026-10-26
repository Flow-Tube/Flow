package io.github.aedev.flow.ui.components.shared.quickactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.FlowRowGroup
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.FlowSheetPresenter
import io.github.aedev.flow.ui.components.shared.LocalFlowSheetPresenter
import io.github.aedev.flow.ui.components.shared.connectedButtonShapes
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState
import kotlinx.coroutines.launch

/** The values every quick actions sheet shares. */
object QuickActionsDefaults {
    /** The artwork beside a video's title: one row thumbnail, as a list would show it. */
    val VideoArtworkWidth: Dp = 112.dp

    /** Square artwork for a song, album or playlist. */
    val SquareArtworkSize: Dp = 56.dp

    /** The avatar on a channel or artist row, the same size as a stacked card's avatar. */
    val AvatarSize: Dp = 40.dp

    val PrimaryActionHeight: Dp = ButtonDefaults.MediumContainerHeight

    internal val HeaderPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    internal val PrimaryGroupPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    internal val PrimaryContentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    internal val PrimaryIconSize = 22.dp
    internal val BottomPadding = 16.dp
}

/**
 * Closes a [QuickActionsSheet] the way a drag does: the sheet animates out first. [close] then
 * reports the dismissal; [hideThen] hands off to something else, such as a second sheet.
 */
@Stable
class QuickActionsSheetController internal constructor(
    private val hideThen: (after: () -> Unit) -> Unit,
    private val onDismiss: () -> Unit,
) {
    fun close() = hideThen(onDismiss)

    fun hideThen(after: () -> Unit) = hideThen.invoke(after)
}

/**
 * A media item's menu: an M3 modal sheet that opens half way when its content is taller than half
 * the screen and expands as it is dragged or scrolled up, or opens in full with [expandFully], for a
 * sheet whose last row is what it is for. Every way out animates the sheet away.
 * [page] names the page on show, so switching pages keeps one sheet and resets its scroll; on a page
 * ([onBack] set) back returns to the menu instead of closing it. A [LocalFlowSheetPresenter] shows
 * the same content its own way instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    onDismiss: () -> Unit,
    page: Any? = null,
    onBack: (() -> Unit)? = null,
    expandFully: Boolean = false,
    content: @Composable ColumnScope.(QuickActionsSheetController) -> Unit,
) {
    val presenter = LocalFlowSheetPresenter.current
    if (presenter != null) {
        PresentedQuickActionsSheet(presenter, onDismiss, page, onBack, content)
        return
    }
    val sheetState = rememberFlowSheetState(skipPartiallyExpanded = expandFully)
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val controller =
        remember(sheetState, scope) {
            QuickActionsSheetController(
                hideThen = { after -> scope.launch { sheetState.hide() }.invokeOnCompletion { after() } },
                onDismiss = { latestOnDismiss() },
            )
        }
    val scrollState = remember(page) { ScrollState(initial = 0) }
    val properties = remember(onBack == null) { ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null) }

    // One lambda for the sheet's lifetime: its hosts (the music player, the shell) recompose often, and
    // a new dismiss callback each time makes the dialog window re-apply its parameters mid-animation.
    val dismissRequest = remember { { latestOnDismiss() } }

    ModalBottomSheet(
        onDismissRequest = dismissRequest,
        sheetState = sheetState,
        properties = properties,
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        BackHandler(enabled = onBack != null) { onBack?.invoke() }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(bottom = QuickActionsDefaults.BottomPadding),
        ) {
            content(controller)
        }
    }
}

/** A presenter has no hide animation of its own to wait for, so every way out closes at once. */
@Composable
private fun PresentedQuickActionsSheet(
    presenter: FlowSheetPresenter,
    onDismiss: () -> Unit,
    page: Any?,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.(QuickActionsSheetController) -> Unit,
) {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val controller = remember { QuickActionsSheetController(hideThen = { after -> after() }, onDismiss = { latestOnDismiss() }) }
    val scrollState = remember(page) { ScrollState(initial = 0) }
    presenter.Present(onDismiss = { latestOnDismiss() }, onBack = onBack) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(bottom = QuickActionsDefaults.BottomPadding),
        ) {
            content(controller)
        }
    }
}

/** The item the menu is about: its artwork, title and who made it. Read as a heading, not a button. */
@Composable
fun QuickActionsHeader(
    title: String,
    subtitle: String?,
    artwork: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(QuickActionsDefaults.HeaderPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artwork()
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The title row of a page inside the sheet, with the way back to the menu. */
@Composable
fun QuickActionsPageHeader(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    FlowSheetHeader(
        title = title,
        onClose = onClose,
        onBack = onBack,
        showDragHandle = false,
        dividerAlpha = null,
    )
}

/** One of the primary actions; a non-null [checked] makes it a toggle that shows its state. */
@Immutable
data class QuickPrimaryAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val checked: Boolean? = null,
    val checkedIcon: ImageVector = icon,
)

/**
 * The item's most common actions as one connected M3 Expressive group, icon above label so the
 * existing, translated labels keep their width. A label that is still too long shrinks a step.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuickActionsPrimaryGroup(actions: List<QuickPrimaryAction>) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(QuickActionsDefaults.PrimaryGroupPadding),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        actions.forEachIndexed { index, action ->
            val shapes = connectedButtonShapes(index = index, count = actions.size)
            val modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = QuickActionsDefaults.PrimaryActionHeight)
            val checked = action.checked
            if (checked == null) {
                FilledTonalButton(
                    onClick = action.onClick,
                    shapes = ButtonShapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
                    contentPadding = QuickActionsDefaults.PrimaryContentPadding,
                    modifier = modifier,
                ) {
                    PrimaryActionContent(action.icon, action.label)
                }
            } else {
                TonalToggleButton(
                    checked = checked,
                    onCheckedChange = {
                        haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        action.onClick()
                    },
                    shapes = shapes,
                    contentPadding = QuickActionsDefaults.PrimaryContentPadding,
                    modifier = modifier,
                ) {
                    PrimaryActionContent(if (checked) action.checkedIcon else action.icon, action.label)
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionContent(
    icon: ImageVector,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(QuickActionsDefaults.PrimaryIconSize))
        val typography = MaterialTheme.typography
        Text(
            text = label,
            style = typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = typography.labelSmall.fontSize, maxFontSize = typography.labelMedium.fontSize),
        )
    }
}

/** One row of a [QuickActionsGroup]: draws itself with the segmented shape its position gives it. */
@Immutable
class QuickActionRow(
    val key: String,
    val content: @Composable (shape: Shape) -> Unit,
)

/**
 * A titled group of rows drawn as one segmented surface, as the settings pages draw theirs. Rows a
 * screen can't handle are left out of [rows] by the caller, so an empty group draws nothing.
 */
@Composable
fun QuickActionsGroup(
    title: String?,
    rows: List<QuickActionRow>,
) {
    if (rows.isEmpty()) return
    if (title != null) FlowSectionHeader(text = title)
    FlowRowGroup {
        rows.forEachIndexed { index, row ->
            key(row.key) { row.content(flowRowGroupShape(index = index, count = rows.size)) }
        }
    }
}
