package io.github.aedev.flow.ui.components.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape

internal val SettingsRowGap = FlowSegmentedGap
private val GroupHeaderTopPadding = 8.dp

/** Lines the header text up with the row titles below it, whose own padding is 16 dp. */
private val GroupHeaderInset = 12.dp

@DslMarker
annotation class SettingsDsl

/**
 * Builds a settings page. Every item is keyed, which is what lets a search result scroll to it,
 * and rows added through [group] get their segmented shapes computed here — no page places
 * dividers or picks corner shapes by hand.
 */
@SettingsDsl
class SettingsListScope internal constructor(
    private val lazy: LazyListScope,
    private val keys: SettingsKeyRecorder,
    internal val onTv: Boolean = false,
) {
    /** A free-standing item, such as a preview card or a notice between groups. */
    fun item(
        key: String,
        content: @Composable () -> Unit,
    ) {
        lazy.item(key = keys.add(key)) {
            SettingsColumnFrame(Modifier.padding(horizontal = SettingsHorizontalPadding)) {
                SettingsHighlightFrame(key = key, shape = RectangleShape) { content() }
            }
        }
    }

    /** A section title on its own, for a section whose body is a single free-standing [item]. */
    fun header(
        key: String,
        @StringRes text: Int,
    ) {
        lazy.item(key = keys.add(key)) {
            SettingsColumnFrame(Modifier.padding(horizontal = GroupHeaderInset)) {
                SettingsHighlightFrame(key = key, shape = RectangleShape) {
                    FlowSectionHeader(
                        text = stringResource(text),
                        modifier = Modifier.padding(top = GroupHeaderTopPadding),
                    )
                }
            }
        }
    }

    /**
     * A titled group of rows drawn as one segmented surface. A group whose builder adds no rows is
     * skipped entirely, header and footer included. The header item is keyed [key], so an entry for
     * a whole list of choices (a radio group) can point search at the group itself.
     */
    fun group(
        key: String,
        @StringRes header: Int? = null,
        @StringRes footer: Int? = null,
        rows: SettingsGroupScope.() -> Unit,
    ) {
        val built = SettingsGroupScope(onTv).apply(rows).rows
        if (built.isEmpty()) return
        if (header != null) header(key = key, text = header)
        built.forEachIndexed { index, row ->
            lazy.item(key = keys.add(row.key)) {
                val shape = flowRowGroupShape(index = index, count = built.size)
                SettingsColumnFrame(Modifier.padding(horizontal = SettingsHorizontalPadding)) {
                    SettingsHighlightFrame(key = row.key, shape = shape) { row.content(shape) }
                }
            }
        }
        if (footer != null) {
            item("$key#footer") {
                SettingsFootnote(stringResource(footer))
            }
        }
    }
}

/** The rows of one [SettingsListScope.group]. A row whose entry is not for this device ([onTv]) is left out. */
@SettingsDsl
class SettingsGroupScope internal constructor(
    private val onTv: Boolean = false,
) {
    internal val rows = mutableListOf<SettingsRowSpec>()

    /** A row drawn by the caller in the [Shape] its position in the group calls for. */
    fun row(
        key: String,
        content: @Composable (shape: Shape) -> Unit,
    ) {
        rows += SettingsRowSpec(key, content)
    }

    /** [row] for an indexed option, skipped where the option does not exist. */
    internal fun entryRow(
        entry: SettingEntry,
        content: @Composable (shape: Shape) -> Unit,
    ) {
        if (entry.availability.shownOn(onTv)) row(entry.key, content)
    }
}

internal class SettingsRowSpec(
    val key: String,
    val content: @Composable (Shape) -> Unit,
)
