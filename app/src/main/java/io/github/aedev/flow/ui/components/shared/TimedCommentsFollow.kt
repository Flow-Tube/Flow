package io.github.aedev.flow.ui.components.shared

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.utils.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

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
    val seconds = remember(comments) { comments.map { it.timedSeconds() ?: 0L } }
    return remember(seconds) {
        derivedStateOf { latestPosition?.let { timedAnchorIndex(seconds, it()) } ?: NO_ANCHOR }
    }
}

/**
 * Brings each newly reached comment into view: it joins the top of the list, and a list kept by key
 * would otherwise hold the row that was first and leave the new one just above the screen. Someone
 * reading or dragging holds this off ([TimedFollowPause]).
 */
@Composable
internal fun FollowTimedReveal(
    listState: LazyListState,
    anchor: State<Int>,
    pause: TimedFollowPause,
) {
    LaunchedEffect(listState, pause) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> pause.holdWhileDragging()
                is DragInteraction.Stop, is DragInteraction.Cancel -> pause.afterDrag()
            }
        }
    }
    LaunchedEffect(listState, anchor, pause) {
        snapshotFlow { anchor.value }.drop(1).collectLatest {
            if (pause.isPaused) return@collectLatest
            try {
                listState.animateScrollToItem(0)
            } catch (e: CancellationException) {
                // A drag takes over the scroll and cancels this animation, not the collector.
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

/**
 * The row under the reached comments: when the next one comes, as a time that seeks there, or that
 * there are no more. [waiting] is before the first one, when the row is all there is.
 */
@Composable
internal fun TimedCommentsNextRow(
    nextSeconds: Long?,
    waiting: Boolean,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnSeek by rememberUpdatedState(onSeekMs)
    val time = nextSeconds?.let { formatDuration(it.toInt()) }
    val full =
        when {
            time == null -> stringResource(R.string.comments_timed_end)
            waiting -> stringResource(R.string.comments_timed_waiting, time)
            else -> stringResource(R.string.comments_timed_next, time)
        }
    val linkColor = MaterialTheme.colorScheme.primary
    val text =
        remember(full, time, linkColor) {
            buildAnnotatedString {
                append(full)
                val start = time?.let(full::lastIndexOf) ?: -1
                if (time != null && start >= 0 && nextSeconds != null) {
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "timed_next",
                            styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)),
                        ) { latestOnSeek(nextSeconds * 1_000L) },
                        start,
                        start + time.length,
                    )
                }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
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
