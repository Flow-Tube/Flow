package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.card.VideoCardSkeleton

/**
 * Placeholder cards in the exact plan the loaded feed will use, so the first page does not jump.
 * [stackedInset] matches the real stacked cards' `useInternalPadding`.
 */
@Composable
fun FeedGridSkeleton(
    layout: FeedGridLayout,
    listMode: Boolean,
    modifier: Modifier = Modifier,
    stackedInset: Boolean = true,
    placeholderCount: Int = DEFAULT_PLACEHOLDER_COUNT,
    compactRows: Boolean = false,
    compactRowSpacing: Dp = 0.dp,
    topPadding: Dp = FeedGridTopPadding,
) {
    val plan =
        remember(layout, listMode, placeholderCount, compactRows, compactRowSpacing) {
            feedGridPlanFor(
                layout = layout,
                listMode = listMode,
                itemCount = placeholderCount,
                spansOwnRow = { false },
                includeLastRun = false,
                compactRows = compactRows,
                compactRowSpacing = compactRowSpacing,
            )
        }
    LazyVerticalGrid(
        columns = plan.cells,
        modifier = modifier.fillMaxSize(),
        contentPadding = plan.contentPadding(top = topPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
        userScrollEnabled = false,
    ) {
        items(count = placeholderCount, key = { "skeleton:$it" }, contentType = { "skeleton" }) { index ->
            VideoCardSkeleton(
                layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                useInternalPadding = stackedInset,
                thumbnailWidth = plan.listThumbnailWidth,
                density = plan.cardDensity,
            )
        }
    }
}

private const val DEFAULT_PLACEHOLDER_COUNT = 8
