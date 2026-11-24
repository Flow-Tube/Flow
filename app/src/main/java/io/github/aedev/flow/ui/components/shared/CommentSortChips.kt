package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.innertube.pages.VideoCommentSort

private val ChipSpacing = 8.dp

/**
 * The orders a comment section offers, one selected at a time. Top and Newest are the entries of
 * YouTube's own sort menu, with the names it sends in the user's language; until a page has brought
 * the menu, both show under their usual names. Oldest is Newest read to its end and turned around,
 * since YouTube has no such order. Timed, on surfaces that show it, is YouTube's Timed filter.
 * [note] is a line under the chips about what the selected order shows.
 */
@Composable
fun CommentSortChips(
    selected: CommentSortFilter,
    onSelect: (CommentSortFilter) -> Unit,
    modifier: Modifier = Modifier,
    sortOptions: List<VideoCommentSort> = emptyList(),
    showTimed: Boolean = false,
    note: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            CommentSortFilter.entries.forEach { filter ->
                val index = filter.menuIndex
                val offered =
                    when {
                        filter == CommentSortFilter.TIMED -> showTimed
                        sortOptions.isEmpty() -> true
                        index == null -> sortOptions.size > 1
                        else -> index < sortOptions.size
                    }
                if (!offered) return@forEach
                FlowFilterChip(
                    label = index?.let { sortOptions.getOrNull(it)?.title?.takeIf(String::isNotBlank) } ?: stringResource(filter.labelRes),
                    selected = selected == filter,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(filter)
                    },
                )
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
