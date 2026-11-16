package io.github.aedev.flow.ui.screens.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.flowSegmentShape

// The sync flow's building blocks. Every step is a header, a body of the same segmented rows the
// settings pages use, and a bottom action row, so the flow reads as one screen changing.

/**
 * The heading for a step: an icon in a tonal circle, a title, and one supporting line.
 * Centered, because each step is a single-focus, full-width task.
 */
@Composable
internal fun SyncStepHeader(
    icon: ImageVector,
    /** Omitted when the top app bar already carries this step's title, to avoid saying it twice. */
    title: String? = null,
    body: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = containerColor, contentColor = contentColor, shape = CircleShape) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(16.dp).size(28.dp))
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// The page pads 16 dp; a Settings page's section labels sit 12 dp in, over rows padded 16 dp.
private val SectionLabelShift = (-4).dp

/** A labelled part of the page, as a Settings page draws one: the section label, then its [content]. */
@Composable
internal fun SyncSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FlowSectionHeader(text = title, modifier = Modifier.offset(x = SectionLabelShift))
        content()
    }
}

/** A run of [count] rows drawn as one segmented group, each given the shape its position calls for. */
@Composable
internal fun SyncRowGroup(
    count: Int,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int, shape: Shape) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap)) {
        repeat(count) { index -> row(index, flowSegmentShape(index = index, count = count)) }
    }
}

/** One route through the flow: what it does and when to pick it. */
internal class SyncOption(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val onClick: () -> Unit,
)

/** The routes a step offers, as one group of navigation rows. */
@Composable
internal fun SyncOptions(options: List<SyncOption>) {
    SyncRowGroup(count = options.size) { index, shape ->
        val option = options[index]
        FlowNavRow(
            title = option.title,
            supportingText = option.body,
            leadingIcon = option.icon,
            shape = shape,
            onClick = option.onClick,
        )
    }
}

// The same padding and icon size as the FlowNavRows it sits among.
private val InfoItemPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
private val InfoItemIconSize = 22.dp

/** A read-only row in a group: a collection agreed to, or what a finished sync changed in it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SyncInfoItem(
    icon: ImageVector,
    title: String,
    shape: Shape,
    supporting: String? = null,
) {
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = InfoItemPadding,
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(InfoItemIconSize)) },
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/** A read-only fact about the session (network address, backup note) in a low-emphasis row. */
@Composable
internal fun SyncInfoRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The step's actions. One action fills the width; two share it evenly with the confirming action on
 * the trailing side, so the decisive button always sits where the thumb expects it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SyncActionRow(
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dismissLabel != null && onDismiss != null) {
            OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Text(dismissLabel)
            }
        }
        Button(onClick = onConfirm, enabled = confirmEnabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
            Text(confirmLabel)
        }
    }
}

/** Frames arbitrary content as a single segmented row, the way a settings page frames one. */
@Composable
internal fun SyncCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = flowSegmentShape(index = 0, count = 1),
        color = containerColor,
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}
