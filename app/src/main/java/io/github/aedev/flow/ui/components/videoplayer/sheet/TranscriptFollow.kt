package io.github.aedev.flow.ui.components.videoplayer.sheet

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState

private const val LEAD_VIEWPORT_DIVISOR = 4

/**
 * How many pixels of the list stay above the line being spoken while the transcript follows
 * playback: a quarter of the visible list for context, shrunk so a long line still ends inside it.
 *
 * Measured in pixels rather than items because a cue can wrap to five rows: two whole cues of lead
 * pushed the spoken one past the bottom edge of a short list (#2038).
 *
 * @param viewportPx the height the rows can show in, without the list's content padding.
 * @param linePx the spoken line's measured height, or null while it is not laid out yet.
 */
internal fun transcriptFollowLead(
    viewportPx: Int,
    linePx: Int?,
): Int {
    if (viewportPx <= 0) return 0
    val quarter = viewportPx / LEAD_VIEWPORT_DIVISOR
    if (linePx == null) return quarter
    return (viewportPx - linePx).coerceIn(0, quarter)
}

/**
 * Parks item [index] [transcriptFollowLead] pixels below the top of the list. A line already on
 * screen glides there in one animation; one further away is brought in first, then eased into place.
 */
internal suspend fun LazyListState.followTo(index: Int) {
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        val delta = visible.offset - transcriptFollowLead(followViewportPx(), visible.size)
        if (delta != 0) animateScrollBy(delta.toFloat())
        return
    }
    animateScrollToItem(index)
    val line = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size
    val lead = transcriptFollowLead(followViewportPx(), line)
    if (lead > 0) animateScrollBy(-lead.toFloat())
}

private fun LazyListState.followViewportPx(): Int =
    layoutInfo.viewportSize.height - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding
