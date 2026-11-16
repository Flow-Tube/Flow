package io.github.aedev.flow.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.components.shared.card.VideoCardDensity
import org.junit.Test

/**
 * Pins the one grid decision Home, Subscriptions, Categories and Search share: a compact window is
 * a single edge-to-edge column, and every wider window is filled by the platform's own adaptive
 * derivation rather than by a table of widths.
 */
class FeedGridLayoutTest {
    private fun columnsAt(width: Dp) = feedGridLayoutFor(width, maxAutoColumns = Int.MAX_VALUE).columns

    private fun cappedColumnsAt(width: Dp) = feedGridLayoutFor(width).columns

    @Test
    fun `a compact window is one edge to edge column`() {
        listOf(0.dp, 360.dp, 411.dp, 480.dp, 599.dp).forEach { width ->
            val layout = feedGridLayoutFor(width)

            assertThat(layout.columns).isEqualTo(1)
            assertThat(layout.cells).isEqualTo(GridCells.Fixed(1))
            assertThat(layout.contentPadding).isEqualTo(0.dp)
        }
    }

    @Test
    fun `a medium window gains a second column and puts thumbnails on the sixteen dp margin`() {
        val layout = feedGridLayoutFor(600.dp)

        assertThat(layout.columns).isEqualTo(2)
        assertThat(layout.contentPadding + VideoCardDefaults.Inset).isEqualTo(16.dp)
    }

    @Test
    fun `wide windows let the platform derive the count`() {
        listOf(600.dp, 840.dp, 1200.dp).forEach { width ->
            assertThat(feedGridLayoutFor(width, maxAutoColumns = Int.MAX_VALUE).cells)
                .isInstanceOf(GridCells.Adaptive::class.java)
        }
    }

    @Test
    fun `the counts the width tables produced survive at their own breakpoints`() {
        assertThat(columnsAt(700.dp)).isEqualTo(2)
        assertThat(columnsAt(800.dp)).isEqualTo(2)
        assertThat(columnsAt(745.dp)).isEqualTo(2)
        assertThat(columnsAt(900.dp)).isEqualTo(3)
        assertThat(columnsAt(1200.dp)).isEqualTo(4)
    }

    @Test
    fun `a wider window never renders fewer columns`() {
        var previous = 0
        var previousCapped = 0
        var width = 200
        while (width <= 2400) {
            val columns = columnsAt(width.dp)
            val capped = cappedColumnsAt(width.dp)
            assertThat(columns).isAtLeast(previous)
            assertThat(capped).isAtLeast(previousCapped)
            previous = columns
            previousCapped = capped
            width++
        }
    }

    @Test
    fun `a capped grid adds a column once its cards would pass the maximum width`() {
        assertThat(cappedColumnsAt(1184.dp)).isEqualTo(3)
        assertThat(cappedColumnsAt(1600.dp)).isEqualTo(4)
        assertThat(cappedColumnsAt(1824.dp)).isEqualTo(4)
        assertThat(cappedColumnsAt(2000.dp)).isEqualTo(5)
        listOf(900.dp, 1184.dp, 1600.dp, 1824.dp, 2400.dp).forEach { width ->
            assertThat(feedGridLayoutFor(width).cardWidth.value).isAtMost(460f)
        }
    }

    @Test
    fun `a pinned preference overrides the width in both directions`() {
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.THREE).columns).isEqualTo(3)
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.THREE).cells).isEqualTo(GridCells.Fixed(3))
        assertThat(feedGridLayoutFor(1400.dp, HomeFeedColumns.ONE).cells).isEqualTo(GridCells.Fixed(1))
    }

    @Test
    fun `an auto cap stops the derivation without touching narrower windows`() {
        assertThat(feedGridLayoutFor(900.dp, maxAutoColumns = 3).columns).isEqualTo(3)
        assertThat(feedGridLayoutFor(900.dp, maxAutoColumns = 3).cells).isInstanceOf(GridCells.Adaptive::class.java)
        assertThat(feedGridLayoutFor(1200.dp, maxAutoColumns = 3).columns).isEqualTo(3)
        assertThat(feedGridLayoutFor(1200.dp, maxAutoColumns = 3).cells).isEqualTo(GridCells.Fixed(3))
        assertThat(feedGridLayoutFor(1600.dp, maxAutoColumns = 3).columns).isEqualTo(4)
    }

    @Test
    fun `a pinned preference wins over an auto cap`() {
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.THREE, maxAutoColumns = 2).columns).isEqualTo(3)
    }

    @Test
    fun `only the compact band reports itself compact`() {
        assertThat(feedGridLayoutFor(599.dp).isCompact).isTrue()
        assertThat(feedGridLayoutFor(600.dp).isCompact).isFalse()
    }

    @Test
    fun `the large window band keeps its wider gutters`() {
        val layout = feedGridLayoutFor(1200.dp)

        assertThat(layout.contentPadding + VideoCardDefaults.Inset).isEqualTo(24.dp)
        assertThat(layout.cardSpacing).isEqualTo(16.dp)
    }

    @Test
    fun `a wide window pinned to one column keeps row thumbnails one automatic column wide`() {
        val pinned = feedGridLayoutFor(1184.dp, HomeFeedColumns.ONE)
        val auto = feedGridLayoutFor(1184.dp)

        assertThat(pinned.columns).isEqualTo(1)
        assertThat(pinned.thumbnailWidth).isEqualTo(auto.thumbnailWidth)
    }

    @Test
    fun `only a single compact column bleeds its cards to the edge`() {
        assertThat(feedGridLayoutFor(360.dp).cardsBleed).isTrue()
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.ONE).cardsBleed).isTrue()
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.TWO).cardsBleed).isFalse()
        assertThat(feedGridLayoutFor(360.dp, HomeFeedColumns.THREE).cardsBleed).isFalse()
        assertThat(feedGridLayoutFor(745.dp).cardsBleed).isFalse()
        assertThat(feedGridLayoutFor(1400.dp, HomeFeedColumns.ONE).cardsBleed).isFalse()
    }

    @Test
    fun `cells sit edge to edge and a thumbnail is a cell less both insets`() {
        listOf(745.dp, 1184.dp, 1824.dp).forEach { width ->
            val layout = feedGridLayoutFor(width)

            assertThat(layout.rowWidth.value).isWithin(0.01f).of((width - layout.contentPadding * 2).value)
            assertThat(layout.thumbnailWidth).isEqualTo(layout.cardWidth - VideoCardDefaults.Inset * 2)
        }
    }

    @Test
    fun `three columns on a phone make a dense grid with 8 dp to the edge and between thumbnails`() {
        val layout = feedGridLayoutFor(360.dp, HomeFeedColumns.THREE)

        assertThat(layout.dense).isTrue()
        assertThat(layout.cardDensity).isEqualTo(VideoCardDensity.Dense)
        assertThat(layout.cardInset).isEqualTo(4.dp)
        assertThat(layout.contentPadding).isEqualTo(4.dp)
        assertThat(layout.cardSpacing).isEqualTo(8.dp)
        assertThat(layout.thumbnailWidth.value).isWithin(0.01f).of((360f - 4f * 2) / 3 - 4f * 2)
        assertThat(layout.listContentPadding).isEqualTo(0.dp)
    }

    @Test
    fun `two columns on a phone keep the regular spacing`() {
        val layout = feedGridLayoutFor(360.dp, HomeFeedColumns.TWO)

        assertThat(layout.dense).isFalse()
        assertThat(layout.cardDensity).isEqualTo(VideoCardDensity.Regular)
        assertThat(layout.cardInset).isEqualTo(VideoCardDefaults.Inset)
        assertThat(layout.contentPadding).isEqualTo(0.dp)
        assertThat(layout.cardSpacing).isEqualTo(12.dp)
        assertThat(layout.thumbnailWidth).isEqualTo(180.dp - VideoCardDefaults.Inset * 2)
    }

    @Test
    fun `an automatic phone grid is one regular column`() {
        val layout = feedGridLayoutFor(411.dp)

        assertThat(layout.columns).isEqualTo(1)
        assertThat(layout.dense).isFalse()
        assertThat(layout.cardInset).isEqualTo(VideoCardDefaults.Inset)
    }

    @Test
    fun `three pinned columns past the compact band stay regular`() {
        val layout = feedGridLayoutFor(600.dp, HomeFeedColumns.THREE)

        assertThat(layout.columns).isEqualTo(3)
        assertThat(layout.dense).isFalse()
        assertThat(layout.cardInset).isEqualTo(VideoCardDefaults.Inset)
        assertThat(layout.contentPadding + VideoCardDefaults.Inset).isEqualTo(16.dp)
        assertThat(layout.listContentPadding).isEqualTo(layout.contentPadding)
    }
}
