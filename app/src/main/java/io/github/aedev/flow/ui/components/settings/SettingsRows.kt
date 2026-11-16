package io.github.aedev.flow.ui.components.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.ui.components.shared.FlowBadgedTitle
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSelectionRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import kotlinx.coroutines.flow.StateFlow

/**
 * A switch for [entry]. [summary] replaces the entry's own summary when the row needs to say
 * something about the current state.
 */
fun SettingsGroupScope.switch(
    entry: SettingEntry,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    summary: String? = null,
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
) = entryRow(entry) { shape ->
    FlowSwitchRow(
        title = stringResource(entry.title),
        supportingText = summary ?: entry.summaryText(),
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        leadingIcon = icon,
        leadingPainter = iconRes?.let { painterResource(it) },
        shape = shape,
        titleBadge = entry.isNew(),
    )
}

/**
 * A switch that collects its own [state], so flipping it recomposes this row alone rather than the
 * page that declared it.
 */
fun SettingsGroupScope.switch(
    entry: SettingEntry,
    state: StateFlow<Boolean>,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
) = entryRow(entry) { shape ->
    val checked by state.collectAsStateWithLifecycle()
    FlowSwitchRow(
        title = stringResource(entry.title),
        supportingText = entry.summaryText(),
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        leadingIcon = icon,
        leadingPainter = iconRes?.let { painterResource(it) },
        shape = shape,
        titleBadge = entry.isNew(),
    )
}

/**
 * A row that opens a picker for [entry] and shows its current choice, read inside the row by
 * [valueText] so a changed value recomposes this row alone.
 */
fun SettingsGroupScope.choice(
    entry: SettingEntry,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    valueText: @Composable () -> String?,
) = entryRow(entry) { shape ->
    FlowNavRow(
        title = stringResource(entry.title),
        supportingText = valueText() ?: entry.summaryText(),
        onClick = onClick,
        enabled = enabled,
        showChevron = false,
        leadingIcon = icon,
        shape = shape,
        titleBadge = entry.isNew(),
    )
}

/**
 * A row that opens a page, dialog or sheet for [entry]. A [value] is shown under the title — the
 * current choice of a setting that opens a picker — and otherwise the entry's summary. [valueText]
 * is the same for a value read from state, so a change recomposes this row alone.
 */
fun SettingsGroupScope.nav(
    entry: SettingEntry,
    onClick: () -> Unit,
    value: String? = null,
    valueText: (@Composable () -> String?)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    selected: Boolean = false,
    icon: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
    modifier: Modifier = Modifier,
) = entryRow(entry) { shape ->
    FlowNavRow(
        title = stringResource(entry.title),
        modifier = modifier,
        supportingText = valueText?.invoke() ?: value ?: entry.summaryText(),
        onClick = onClick,
        enabled = enabled,
        showChevron = showChevron,
        selected = selected,
        leadingIcon = icon,
        leadingPainter = iconRes?.let { painterResource(it) },
        shape = shape,
        titleBadge = entry.isNew(),
    )
}

/** A row that only reports a value, such as when something last ran. It is not clickable. */
fun SettingsGroupScope.info(
    entry: SettingEntry,
    value: String?,
    icon: ImageVector? = null,
) = entryRow(entry) { shape -> InfoRow(stringResource(entry.title), value ?: entry.summaryText(), icon, shape, badge = entry.isNew()) }

/** [info] for a status that is not a setting, so it stays out of settings search. */
fun SettingsGroupScope.info(
    key: String,
    title: String,
    value: String?,
    icon: ImageVector? = null,
) = row(key) { shape -> InfoRow(title, value, icon, shape) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InfoRow(
    title: String,
    value: String?,
    icon: ImageVector?,
    shape: Shape,
    badge: Boolean = false,
) {
    // A remote scrolls a page only by moving focus, so on one these rows take focus despite doing nothing.
    val focusModifier =
        if (LocalDpadNavigation.current) {
            val interactionSource = remember { MutableInteractionSource() }
            Modifier
                .indication(interactionSource, ripple(focusRingShape = shape))
                .focusable(interactionSource = interactionSource)
        } else {
            Modifier
        }
    SegmentedListItem(
        modifier = focusModifier,
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = icon?.let { { Icon(imageVector = it, contentDescription = null) } },
        supportingContent = value?.let { { Text(it) } },
    ) {
        FlowBadgedTitle(title = title, badge = badge)
    }
}

/** One option of a single-choice list drawn inline on a page rather than in a dialog. */
fun SettingsGroupScope.option(
    key: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true,
) = row(key) { shape ->
    FlowSelectionRow(
        title = label,
        supportingText = supportingText,
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = shape,
    )
}

/** A choice between two to four values, answered in place with a connected toggle group. */
fun <T> SettingsGroupScope.toggleGroup(
    entry: SettingEntry,
    options: List<FlowToggleOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
    summary: String? = null,
    preview: (@Composable () -> Unit)? = null,
) = entryRow(entry) { shape ->
    SettingsToggleGroupRow(
        title = stringResource(entry.title),
        summary = summary ?: entry.summaryText(),
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
        shape = shape,
        preview = preview,
        titleBadge = entry.isNew(),
    )
}

/** A toggle group that collects its own [state]; see the state-backed [switch]. */
fun <T> SettingsGroupScope.toggleGroup(
    entry: SettingEntry,
    options: List<FlowToggleOption<T>>,
    state: StateFlow<T>,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) = entryRow(entry) { shape ->
    val selected by state.collectAsStateWithLifecycle()
    SettingsToggleGroupRow(
        title = stringResource(entry.title),
        summary = entry.summaryText(),
        options = options,
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
        shape = shape,
        titleBadge = entry.isNew(),
    )
}

/**
 * A numeric setting on a slider. The value is only written when the drag ends, so a DataStore
 * write never runs per frame.
 */
fun SettingsGroupScope.slider(
    entry: SettingEntry,
    value: Float,
    onValueCommitted: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: @Composable (Float) -> String,
    steps: Int = 0,
    enabled: Boolean = true,
    summary: String? = null,
    centered: Boolean = false,
) = entryRow(entry) { shape ->
    SettingsSliderRow(
        title = stringResource(entry.title),
        summary = summary ?: entry.summaryText(),
        value = value,
        onValueCommitted = onValueCommitted,
        valueRange = valueRange,
        steps = steps,
        valueLabel = valueLabel,
        enabled = enabled,
        shape = shape,
        titleBadge = entry.isNew(),
        centered = centered,
    )
}

/** A notice between groups; see [SettingsNotice]. */
fun SettingsListScope.notice(
    key: String,
    text: @Composable () -> String,
    icon: ImageVector? = null,
    title: (@Composable () -> String)? = null,
    isWarning: Boolean = false,
) = item(key) {
    SettingsNotice(text = text(), icon = icon, title = title?.invoke(), isWarning = isWarning)
}

@Composable
private fun SettingEntry.summaryText(): String? = summary?.let { stringResource(it) }

/** Whether this option, or a page under it, is marked as new; see [LocalNewSettings]. */
@Composable
internal fun SettingEntry.isNew(): Boolean = key in LocalNewSettings.current
