package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val RowHorizontalPadding = 20.dp
private val GroupedRowHorizontalPadding = 16.dp
private val GroupHorizontalPadding = 12.dp
private val SelectionRowVerticalPadding = 16.dp
private val NavRowVerticalPadding = 14.dp
private val SwitchRowVerticalPadding = 6.dp
private val RowLeadingIconSize = 22.dp
private val NavRowTrailingIconSize = 20.dp
private val NavRowTrailingSpacing = 2.dp
private val SectionHeaderPadding =
    PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)

private const val DISABLED_CONTENT_ALPHA = 0.4f

/**
 * A single-choice row. [Role.RadioButton] comes from the library's selectable [SegmentedListItem],
 * which also morphs the row's corners while it is pressed or selected.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSelectionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingIcon: ImageVector? = null,
    showSelectedContainer: Boolean = true,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
) {
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.shapes(shape = shape),
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        supportingContent = rowSupportingContent(supportingText),
        leadingContent = rowLeadingContent(leadingIcon, null),
        trailingContent =
            if (selected) {
                { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            },
        colors = rowColors(shape = shape, showSelectedContainer = showSelectedContainer),
        contentPadding = rowPadding(shape, SelectionRowVerticalPadding),
    ) {
        Text(text = title)
    }
}

/**
 * A row that opens another page or runs an action: an optional current value, then a chevron.
 * [selected] marks the page currently open beside a two-pane list. [leadingContent] and
 * [trailingContent] replace the icon and the value-and-chevron with any content, such as an avatar
 * or a button, and [supportingContent] the supporting text; [destructive] draws the title and icon in the error colour; [stateDescription] tells
 * TalkBack the row's current state, for an action that toggles.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingIcon: ImageVector? = null,
    leadingPainter: Painter? = null,
    trailingText: String? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = RectangleShape,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    stateDescription: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val trailing: (@Composable () -> Unit)? =
        if (trailingContent != null) {
            trailingContent
        } else if (showChevron || !trailingText.isNullOrBlank()) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!trailingText.isNullOrBlank()) {
                        Text(
                            text = trailingText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (showChevron) {
                        if (!trailingText.isNullOrBlank()) Spacer(Modifier.width(NavRowTrailingSpacing))
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(NavRowTrailingIconSize),
                        )
                    }
                }
            }
        } else {
            null
        }

    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.shapes(shape = shape),
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription } else Modifier),
        enabled = enabled,
        supportingContent = supportingContent ?: rowSupportingContent(supportingText),
        leadingContent = leadingContent ?: rowLeadingContent(leadingIcon, leadingPainter),
        trailingContent = trailing,
        colors = rowColors(shape = shape, showSelectedContainer = true, destructive = destructive),
        contentPadding = rowPadding(shape, NavRowVerticalPadding),
    ) {
        Text(text = title)
    }
}

/**
 * A row whose whole width toggles its trailing [Switch]. The switch itself is not clickable so the
 * row is a single [Role.Switch] target. The library's toggleable overload is not used because it
 * reports [Role.Checkbox] and tints the whole row while checked, which is a checklist, not a switch.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingIcon: ImageVector? = null,
    leadingPainter: Painter? = null,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
) {
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.shapes(shape = shape),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                },
        enabled = enabled,
        supportingContent = rowSupportingContent(supportingText),
        leadingContent = rowLeadingContent(leadingIcon, leadingPainter),
        trailingContent = {
            FlowSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        },
        colors = rowColors(shape = shape, showSelectedContainer = false),
        contentPadding = rowPadding(shape, SwitchRowVerticalPadding),
    ) {
        Text(text = title)
    }
}

/** The section label above a group of [FlowSelectionRow]/[FlowNavRow]/[FlowSwitchRow]s. */
@Composable
fun FlowSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(SectionHeaderPadding),
    )
}

private fun rowSupportingContent(supportingText: String?): (@Composable () -> Unit)? = supportingText?.let { text -> { Text(text = text) } }

private fun rowLeadingContent(
    leadingIcon: ImageVector?,
    leadingPainter: Painter?,
): (@Composable () -> Unit)? =
    when {
        leadingIcon != null -> {
            {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(RowLeadingIconSize),
                )
            }
        }

        leadingPainter != null -> {
            {
                Icon(
                    painter = leadingPainter,
                    contentDescription = null,
                    modifier = Modifier.size(RowLeadingIconSize),
                )
            }
        }

        else -> {
            null
        }
    }

/**
 * Lays rows out as one Material 3 segmented group: the outer rows carry the group's rounded ends,
 * the rest the small corners, with the gap the list-item tokens define between them.
 */
@Composable
fun FlowRowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = GroupHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap),
        content = content,
    )
}

/** The shape of row [index] of [count] inside a [FlowRowGroup]. */
@Composable
fun flowRowGroupShape(
    index: Int,
    count: Int,
): Shape = flowSegmentShape(index = index, count = count)

/**
 * Colours for a row: a grouped row sits on its own container so the segmented group reads as one
 * surface, an ungrouped row stays transparent on whatever sheet or page holds it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rowColors(
    shape: Shape,
    showSelectedContainer: Boolean,
    destructive: Boolean = false,
): ListItemColors {
    val container = groupedContainerColor(shape)
    val colors = MaterialTheme.colorScheme
    return ListItemDefaults.segmentedColors(
        containerColor = container,
        contentColor = if (destructive) colors.error else colors.onSurface,
        leadingContentColor = if (destructive) colors.error else colors.onSurfaceVariant,
        trailingContentColor = colors.onSurfaceVariant,
        supportingContentColor = colors.onSurfaceVariant,
        disabledContainerColor = container,
        disabledContentColor = colors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        disabledLeadingContentColor = colors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
        disabledTrailingContentColor = colors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
        disabledSupportingContentColor = colors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
        selectedContainerColor = if (showSelectedContainer) colors.secondaryContainer else container,
        selectedContentColor = if (showSelectedContainer) colors.onSecondaryContainer else colors.onSurface,
        selectedLeadingContentColor =
            if (showSelectedContainer) colors.onSecondaryContainer else colors.onSurfaceVariant,
        selectedTrailingContentColor = if (showSelectedContainer) colors.onSecondaryContainer else colors.primary,
        selectedSupportingContentColor =
            if (showSelectedContainer) colors.onSecondaryContainer else colors.onSurfaceVariant,
    )
}

@Composable
private fun groupedContainerColor(shape: Shape): Color =
    if (shape == RectangleShape) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh

private fun rowHorizontalPadding(shape: Shape): Dp = if (shape == RectangleShape) RowHorizontalPadding else GroupedRowHorizontalPadding

private fun rowPadding(
    shape: Shape,
    vertical: Dp,
): PaddingValues = PaddingValues(horizontal = rowHorizontalPadding(shape), vertical = vertical)
