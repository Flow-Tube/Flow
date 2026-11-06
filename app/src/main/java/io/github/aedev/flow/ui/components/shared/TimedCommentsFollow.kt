package io.github.aedev.flow.ui.components.shared

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

private const val NO_ANCHOR = -1
private const val DRAG_PAUSE_MS = 5_000L
private const val REPLIES_PAUSE_MS = 20_000L

/**
 * What a surface that can follow its video gives the Timed list. [isSurfaceVisible] stays false while
 * the player is collapsed: the sheet is kept composed behind the mini player, and following there
 * would scroll a list nobody can see.
 */
class CommentsTimedFollow(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val isSurfaceVisible: Boolean,
    val positionMs: () -> Long,
)

/** The switch under the chips while Timed is selected. */
@Composable
fun CommentsTimedFollowRow(
    follow: CommentsTimedFollow,
    modifier: Modifier = Modifier,
) {
    FlowSwitchRow(
        title = stringResource(R.string.comments_timed_follow),
        checked = follow.enabled,
        onCheckedChange = follow.onEnabledChange,
        modifier = modifier,
    )
}

/** Holds following off while someone reads, so the list never moves under their thumb. */
internal class TimedFollowPause {
    private var until = 0L

    val isPaused: Boolean get() = SystemClock.elapsedRealtime() < until

    fun holdWhileDragging() {
        until = Long.MAX_VALUE
    }

    fun afterDrag() = pauseFor(DRAG_PAUSE_MS)

    fun afterOpeningReplies() = pauseFor(REPLIES_PAUSE_MS)

    fun resume() {
        until = 0L
    }

    private fun pauseFor(millis: Long) {
        until = SystemClock.elapsedRealtime() + millis
    }
}

/**
 * The row holding the latest timestamp already passed. Derived, so the position can tick as it likes
 * and only a crossing reaches anything that reads this.
 */
@Composable
internal fun rememberTimedAnchor(
    comments: List<Comment>,
    positionMs: (() -> Long)?,
): State<Int> {
    val latestPosition by rememberUpdatedState(positionMs)
    val seconds = remember(comments) { comments.map { it.firstTimestampSeconds() ?: 0L } }
    return remember(seconds) {
        derivedStateOf { latestPosition?.let { timedAnchorIndex(seconds, it()) } ?: NO_ANCHOR }
    }
}

/**
 * Scrolls to the anchor at each crossing, or to the earliest row before the first one. It watches
 * which comment that is rather than its index: a new page moves the index without anything being
 * crossed, and rows keep their place by key, so that is no reason to pull someone back up the list.
 */
@Composable
internal fun FollowTimedAnchor(
    listState: LazyListState,
    anchor: State<Int>,
    comments: List<Comment>,
    pause: TimedFollowPause,
) {
    val latestComments by rememberUpdatedState(comments)
    LaunchedEffect(listState, pause) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> pause.holdWhileDragging()
                is DragInteraction.Stop, is DragInteraction.Cancel -> pause.afterDrag()
            }
        }
    }
    LaunchedEffect(listState, anchor, pause) {
        snapshotFlow { (latestComments.getOrNull(anchor.value) ?: latestComments.lastOrNull())?.id }.collectLatest { id ->
            val row = latestComments.indexOfFirst { it.id == id }
            if (row < 0 || pause.isPaused) return@collectLatest
            try {
                listState.animateScrollToItem(row)
            } catch (e: CancellationException) {
                // A drag takes over the scroll and cancels this animation, not the collector.
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

/** A comment row, on a highlight while it is the anchor. The colour is read while drawing. */
@Composable
internal fun TimedCommentBackdrop(
    highlighted: Boolean,
    content: @Composable () -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val color by animateColorAsState(
        targetValue = if (highlighted) highlight else highlight.copy(alpha = 0f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "timedCommentHighlight",
    )
    Box(Modifier.drawBehind { drawRect(color) }) { content() }
}
