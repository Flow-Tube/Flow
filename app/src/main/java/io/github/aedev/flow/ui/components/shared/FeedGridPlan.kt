package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.partialRowIndices
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardDensity

/**
 * The per-index decisions a vertical feed grid makes once its [FeedGridLayout] and its item list are
 * known — which rows the grid cannot fill, which cards therefore take the thumbnail-left shape, how
 * wide that thumbnail is, and how far apart the rows sit.
 *
 * Four rules, shared by every feed surface:
 *
 * 1. List mode is always one full-width column, at every screen size.
 * 2. A compact window is one column of edge-to-edge full-width cards — a phone has no room for a
 *    thumbnail-left row. Surfaces that are rows on a phone by design opt out with `compactRows`.
 * 3. A wide window pinned to one column uses thumbnail-left cards instead; a full-width card there
 *    is a 16:9 image the size of the screen.
 * 4. A row the grid cannot fill takes thumbnail-left cards, one per row, and their thumbnails are
 *    exactly one grid column's thumbnail wide so they align with the grid above.
 *
 * Only horizontal padding lives here: a caller's top padding may animate, and it must not rebuild
 * the plan when it does.
 */
@Immutable
data class FeedGridPlan(
    val cells: GridCells,
    val columns: Int,
    val horizontalPadding: Dp,
    val rowSpacing: Dp,
    val listThumbnailWidth: Dp,
    val partialRows: Set<Int>,
    /** Every video card's density in this plan, rows included; list mode is never dense. */
    val cardDensity: VideoCardDensity = VideoCardDensity.Regular,
    /** The side inset a grid's playlist cards take so they line up with its video cards. */
    val cardInset: Dp = VideoCardDefaults.Inset,
    private val listMode: Boolean,
    private val isCompact: Boolean,
    private val compactRows: Boolean,
) {
    fun isListCard(index: Int): Boolean = listMode || index in partialRows || (columns == 1 && (!isCompact || compactRows))

    fun span(
        index: Int,
        spansOwnRow: Boolean,
        maxLineSpan: Int,
    ): GridItemSpan = if (spansOwnRow || index in partialRows) GridItemSpan(maxLineSpan) else GridItemSpan(1)

    fun contentPadding(
        top: Dp,
        bottom: Dp,
    ): PaddingValues = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = top, bottom = bottom)
}

/**
 * [includeLastRun] is false while more pages may still arrive: the tail of the list would otherwise
 * reflow every time a page lands. [compactRowSpacing] is the gap between one-column rows on a phone.
 */
fun feedGridPlanFor(
    layout: FeedGridLayout,
    listMode: Boolean,
    itemCount: Int,
    spansOwnRow: (Int) -> Boolean,
    includeLastRun: Boolean,
    compactRows: Boolean = false,
    compactRowThumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
    compactRowSpacing: Dp = 0.dp,
): FeedGridPlan {
    val columns = if (listMode) 1 else layout.columns
    val dense = layout.dense && !listMode
    return FeedGridPlan(
        cells = if (listMode) GridCells.Fixed(1) else layout.cells,
        columns = columns,
        horizontalPadding = if (listMode) layout.listContentPadding else layout.contentPadding,
        rowSpacing =
            when {
                columns > 1 -> layout.cardSpacing
                layout.isCompact -> compactRowSpacing
                else -> 0.dp
            },
        listThumbnailWidth = if (layout.isCompact) compactRowThumbnailWidth else layout.thumbnailWidth,
        partialRows =
            partialRowIndices(
                spansOwnRow = (0 until itemCount).map(spansOwnRow),
                columns = columns,
                includeLastRun = includeLastRun,
            ),
        cardDensity = if (dense) VideoCardDensity.Dense else VideoCardDensity.Regular,
        cardInset = if (dense) layout.cardInset else VideoCardDefaults.Inset,
        listMode = listMode,
        isCompact = layout.isCompact,
        compactRows = compactRows,
    )
}

@Composable
fun rememberFeedGridPlan(
    layout: FeedGridLayout,
    listMode: Boolean,
    itemCount: Int,
    spansOwnRow: (Int) -> Boolean,
    includeLastRun: Boolean,
    itemsKey: Any? = null,
    compactRows: Boolean = false,
    compactRowThumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
    compactRowSpacing: Dp = 0.dp,
): FeedGridPlan =
    remember(layout, listMode, itemCount, includeLastRun, itemsKey, compactRows, compactRowThumbnailWidth, compactRowSpacing) {
        feedGridPlanFor(
            layout = layout,
            listMode = listMode,
            itemCount = itemCount,
            spansOwnRow = spansOwnRow,
            includeLastRun = includeLastRun,
            compactRows = compactRows,
            compactRowThumbnailWidth = compactRowThumbnailWidth,
            compactRowSpacing = compactRowSpacing,
        )
    }

/** The top padding feed grids open with under their own chrome. */
val FeedGridTopPadding: Dp = 8.dp
