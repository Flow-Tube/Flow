package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.ui.components.FEED_MAX_AUTO_COLUMNS
import io.github.aedev.flow.ui.components.feedGridLayoutFor
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardDensity
import org.junit.Test

/**
 * The four rules every feed grid shares, as pure logic. Video cards call `hiltViewModel()`
 * unconditionally and `src/test` has no Hilt infrastructure, so layout is asserted here rather than
 * by rendering one.
 */
class FeedGridPlanTest {
    private fun plan(
        width: Dp,
        listMode: Boolean = false,
        itemCount: Int = 12,
        spansOwnRow: (Int) -> Boolean = { false },
        includeLastRun: Boolean = true,
    ) = feedGridPlanFor(
        layout = feedGridLayoutFor(width, maxAutoColumns = FEED_MAX_AUTO_COLUMNS),
        listMode = listMode,
        itemCount = itemCount,
        spansOwnRow = spansOwnRow,
        includeLastRun = includeLastRun,
    )

    @Test
    fun `list mode is one full-width column at every size`() {
        listOf(360.dp, 700.dp, 1200.dp).forEach { width ->
            val plan = plan(width, listMode = true)

            assertThat(plan.columns).isEqualTo(1)
            assertThat(plan.cells).isEqualTo(GridCells.Fixed(1))
            assertThat(plan.isListCard(0)).isTrue()
        }
    }

    @Test
    fun `a compact window is one column of full-width cards, never thumbnail-left`() {
        val plan = plan(360.dp)

        assertThat(plan.columns).isEqualTo(1)
        assertThat(plan.isListCard(0)).isFalse()
    }

    @Test
    fun `a wide window pinned to one column uses thumbnail-left cards`() {
        val layout = feedGridLayoutFor(700.dp).copy(columns = 1, isCompact = false)
        val plan =
            feedGridPlanFor(
                layout = layout,
                listMode = false,
                itemCount = 4,
                spansOwnRow = { false },
                includeLastRun = true,
            )

        assertThat(plan.isListCard(0)).isTrue()
    }

    @Test
    fun `a row the grid cannot fill takes thumbnail-left cards`() {
        val plan = plan(1200.dp, itemCount = 7)

        assertThat(plan.columns).isEqualTo(FEED_MAX_AUTO_COLUMNS)
        assertThat(plan.partialRows).containsExactly(6)
        assertThat(plan.isListCard(6)).isTrue()
        assertThat(plan.isListCard(5)).isFalse()
    }

    @Test
    fun `a full last row leaves nothing partial`() {
        assertThat(plan(1200.dp, itemCount = 9).partialRows).isEmpty()
    }

    @Test
    fun `the tail does not reflow while more pages may arrive`() {
        assertThat(plan(1200.dp, itemCount = 7, includeLastRun = false).partialRows).isEmpty()
    }

    @Test
    fun `a run broken by a full-span item leaves its own short row partial`() {
        // Items 0..1 are a run of two under three columns; 3..5 fill a row exactly.
        assertThat(plan(1200.dp, itemCount = 6, spansOwnRow = { it == 2 }).partialRows).containsExactly(0, 1)

        assertThat(plan(1200.dp, itemCount = 7, spansOwnRow = { it == 2 }).partialRows).containsExactly(0, 1, 6)
    }

    @Test
    fun `a partial row spans the whole line, and so does an item that owns its row`() {
        val plan = plan(1200.dp, itemCount = 7)

        assertThat(plan.span(6, spansOwnRow = false, maxLineSpan = 3).currentLineSpan).isEqualTo(3)
        assertThat(plan.span(0, spansOwnRow = true, maxLineSpan = 3).currentLineSpan).isEqualTo(3)
        assertThat(plan.span(0, spansOwnRow = false, maxLineSpan = 3).currentLineSpan).isEqualTo(1)
    }

    @Test
    fun `a one-column grid drops its row spacing unless a phone asks for it`() {
        assertThat(plan(360.dp).rowSpacing).isEqualTo(0.dp)
        assertThat(plan(1200.dp).rowSpacing).isGreaterThan(0.dp)
        assertThat(plan(1200.dp, listMode = true).rowSpacing).isEqualTo(0.dp)

        val spaced =
            feedGridPlanFor(
                layout = feedGridLayoutFor(360.dp),
                listMode = false,
                itemCount = 4,
                spansOwnRow = { false },
                includeLastRun = true,
                compactRowSpacing = 12.dp,
            )
        assertThat(spaced.rowSpacing).isEqualTo(12.dp)
    }

    @Test
    fun `a surface that is rows on a phone keeps them and its own thumbnail width`() {
        val rows =
            feedGridPlanFor(
                layout = feedGridLayoutFor(360.dp),
                listMode = false,
                itemCount = 4,
                spansOwnRow = { false },
                includeLastRun = true,
                compactRows = true,
                compactRowThumbnailWidth = 152.dp,
            )

        assertThat(rows.isListCard(0)).isTrue()
        assertThat(rows.listThumbnailWidth).isEqualTo(152.dp)
    }

    @Test
    fun `a compact thumbnail keeps its fixed width, a wide one matches a grid column`() {
        assertThat(plan(360.dp).listThumbnailWidth).isEqualTo(VideoCardDefaults.RowThumbnailWidth)

        val wide = plan(1200.dp)
        val layout = feedGridLayoutFor(1200.dp, maxAutoColumns = FEED_MAX_AUTO_COLUMNS)
        assertThat(wide.listThumbnailWidth).isEqualTo(layout.cardWidth - VideoCardDefaults.Inset * 2)
    }

    @Test
    fun `the content padding follows the layout's own horizontal inset`() {
        val layout = feedGridLayoutFor(1200.dp, maxAutoColumns = FEED_MAX_AUTO_COLUMNS)
        val padding = plan(1200.dp).contentPadding(top = 8.dp, bottom = 90.dp)

        assertThat(padding.calculateTopPadding()).isEqualTo(8.dp)
        assertThat(padding.calculateBottomPadding()).isEqualTo(90.dp)
        assertThat(layout.contentPadding).isGreaterThan(0.dp)
    }

    @Test
    fun `an empty list produces no partial rows`() {
        assertThat(plan(1200.dp, itemCount = 0).partialRows).isEmpty()
    }

    @Test
    fun `a dense grid makes dense cards and its list mode keeps the regular padding`() {
        val layout = feedGridLayoutFor(360.dp, HomeFeedColumns.THREE)
        val grid = feedGridPlanFor(layout, listMode = false, itemCount = 4, spansOwnRow = { false }, includeLastRun = true)
        val list = feedGridPlanFor(layout, listMode = true, itemCount = 4, spansOwnRow = { false }, includeLastRun = true)

        assertThat(grid.cardDensity).isEqualTo(VideoCardDensity.Dense)
        assertThat(grid.cardInset).isEqualTo(VideoCardDefaults.DenseInset)
        assertThat(grid.horizontalPadding).isEqualTo(4.dp)
        assertThat(grid.rowSpacing).isEqualTo(8.dp)
        assertThat(grid.isListCard(3)).isTrue()
        assertThat(list.cardDensity).isEqualTo(VideoCardDensity.Regular)
        assertThat(list.cardInset).isEqualTo(VideoCardDefaults.Inset)
        assertThat(list.horizontalPadding).isEqualTo(0.dp)
    }
}
